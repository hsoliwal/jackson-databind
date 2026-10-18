#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Hitesh Soliwal and contributors.
"""Replay the report-admission and frozen-input counterexamples without Maven."""
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

sys.dont_write_bytecode = True
SOURCE = Path(__file__).with_name('upstream-qualification.py')
SPEC = importlib.util.spec_from_file_location('jackson_upstream_qualification', SOURCE)
QUALIFICATION = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(QUALIFICATION)


class AdmissionRegression(unittest.TestCase):
    def test_retry_errors_cannot_hide_behind_zero_aggregate_failures(self):
        with tempfile.TemporaryDirectory(prefix='jackson-report-regression-') as temporary:
            folder = Path(temporary)
            for tag in ('flakyFailure', 'rerunFailure', 'flakyError', 'rerunError'):
                with self.subTest(retry_tag=tag):
                    suite = ET.Element('testsuite', tests='60', failures='0', errors='0', skipped='0')
                    for index in range(60):
                        case = ET.SubElement(suite, 'testcase', classname='fixture.AnnotationTest', name=str(index))
                        if index == 0:
                            ET.SubElement(case, tag, message='A retry must not erase this failure')
                    ET.ElementTree(suite).write(folder/'TEST-retry.xml', encoding='utf-8')
                    report = QUALIFICATION.reports(folder)
                    self.assertEqual([problem['kind'] for problem in report['problems']], [tag])
                    with self.assertRaises(QUALIFICATION.QualificationError):
                        QUALIFICATION.check_tests(report, 'jackson-annotations')

    def test_same_paths_with_changed_bytes_fail_the_next_custody_gate(self):
        with tempfile.TemporaryDirectory(prefix='jackson-custody-regression-') as temporary:
            folder = Path(temporary)
            files = {}
            for name in ('harness', 'receipt', 'runtime', 'dependency'):
                path = folder/name
                path.write_bytes(('original '+name).encode())
                files[name] = path
            tests = folder/'upstream/jackson-annotations/test-classes'
            tests.mkdir(parents=True)
            test = tests/'AnnotationTest.class'
            test.write_bytes(b'original compiled test')
            runner = object.__new__(QUALIFICATION.Qualification)
            runner.out = folder
            runner.result = {
                'harness': {'path': str(files['harness']), 'sha256': QUALIFICATION.sha(files['harness'])},
                'runtime_receipt': {'path': str(files['receipt']), 'sha256': QUALIFICATION.sha(files['receipt'])},
                'forks': {component: {'fork_head': pin} for component, pin in QUALIFICATION.PINS.items()},
            }
            runner.runtimes = {'jackson-annotations': {
                'path': str(files['runtime']), 'sha256': QUALIFICATION.sha(files['runtime'])}}
            runner.baselines = {('jackson-annotations', 'default'): {
                'test_classes': QUALIFICATION.tree_hash(tests),
                'test_classpath_artifacts': {str(files['dependency']): QUALIFICATION.sha(files['dependency'])},
            }}
            runner.check_fork = lambda component: {'fork_head': QUALIFICATION.PINS[component]}
            mutations = [
                ('harness', files['harness'], 'INPUT_IDENTITY_MISMATCH'),
                ('source receipt', files['receipt'], 'INPUT_IDENTITY_MISMATCH'),
                ('runtime JAR', files['runtime'], 'RUNTIME_IDENTITY_MISMATCH'),
                ('compiled tests', test, 'INPUT_IDENTITY_MISMATCH'),
                ('baseline dependency', files['dependency'], 'INPUT_IDENTITY_MISMATCH'),
            ]
            for name, path, expected_status in mutations:
                with self.subTest(mutated_input=name):
                    runner.check_input_custody()
                    original = path.read_bytes()
                    try:
                        path.write_bytes(original+b' changed after pre-run admission')
                        with self.assertRaises(QUALIFICATION.QualificationError) as error:
                            runner.check_input_custody()
                        self.assertEqual(error.exception.status, expected_status)
                    finally:
                        path.write_bytes(original)
            runner.check_input_custody()
            runner.check_fork = lambda component: {'fork_head': 'changed HEAD'}
            with self.assertRaises(QUALIFICATION.QualificationError) as error:
                runner.check_input_custody()
            self.assertEqual(error.exception.status, 'SOURCE_PIN_MISMATCH')

    def test_later_artifact_location_requires_identical_bytes_and_original_load_origin(self):
        with tempfile.TemporaryDirectory(prefix='jackson-origin-regression-') as temporary:
            folder = Path(temporary)
            original = folder/'original annotation.jar'
            later = folder/'later annotation.jar'
            with zipfile.ZipFile(original, 'w') as archive:
                archive.writestr('com/fasterxml/jackson/annotation/JsonFormat.class', b'class membership fixture')
            later.write_bytes(original.read_bytes())
            item = {'path': str(original), 'verification_path': str(later),
                    'sha256': QUALIFICATION.sha(original)}
            log = folder/'classload-1.log'
            log.write_text('[class,load] com.fasterxml.jackson.annotation.JsonFormat source: '+original.as_uri()+'\n')
            # The recorded path may no longer exist; the exact byte identity of
            # the supplied later artifact is sufficient for membership lookup.
            original.unlink()
            result = QUALIFICATION.inspect_origins(folder, 'jackson-annotations', 'default',
                                                    {'jackson-annotations': item}, [])
            self.assertEqual(result['status'], 'PASS')
            log.write_text('[class,load] com.fasterxml.jackson.annotation.JsonFormat source: '+later.as_uri()+'\n')
            result = QUALIFICATION.inspect_origins(folder, 'jackson-annotations', 'default',
                                                    {'jackson-annotations': item}, [])
            self.assertEqual(result['status'], 'FAIL')
            later.write_bytes(later.read_bytes()+b' changed after qualification')
            with self.assertRaises(QUALIFICATION.QualificationError) as error:
                QUALIFICATION.inspect_origins(folder, 'jackson-annotations', 'default',
                                                {'jackson-annotations': item}, [])
            self.assertEqual(error.exception.status, 'RUNTIME_IDENTITY_MISMATCH')


if __name__ == '__main__':
    unittest.main(verbosity=2)
