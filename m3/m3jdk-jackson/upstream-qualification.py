#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Hitesh Soliwal and contributors.
"""Qualify pinned Jackson 2.19.2 fork tests against source-built M3JDK JARs.

The fork POMs and sources are never edited. Build output is written to their
normal target directories; receipts and the replay POMs live in --out.
"""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import time
from urllib.parse import unquote, urlparse
import xml.etree.ElementTree as ET
import zipfile

PINS = {
    'jackson-annotations': 'e84b2adf8f4bb9d881d02a1ec4a0c70dcc9c5a00',
    'jackson-core': '1fb76534cd69a94ecbfb23ed6dce334109c1230c',
    'jackson-databind': '1a506043e0644c9cf60c0905474459605fc751d0',
}
MODULES = {
    'jackson-annotations': 'com.fasterxml.jackson.annotation',
    'jackson-core': 'com.fasterxml.jackson.core',
    'jackson-databind': 'com.fasterxml.jackson.databind',
}
COUNTS = {'jackson-annotations': 60, 'jackson-core': 1470, 'jackson-databind': 3601}
CORE_SKIPS = {
    'com.fasterxml.jackson.core.io.NumberOutputTest#divBy1000FullRange',
    'com.fasterxml.jackson.core.read.TestReadHumongousString#testLargeStringDeserialization',
}
TYPE_SKIP = 'com.fasterxml.jackson.databind.typepollution.TypePollutionTest#sample'
REDEFINITIONS = {
    'default': {'com.fasterxml.jackson.databind.type.TypeFactory',
                'com.fasterxml.jackson.core.io.NumberInput'},
    'typepollution': {'com.fasterxml.jackson.databind.ObjectMapper',
                      'com.fasterxml.jackson.core.ObjectCodec',
                      'com.fasterxml.jackson.core.TreeCodec',
                      'com.fasterxml.jackson.core.Versioned'},
}
NS = 'http://maven.apache.org/POM/4.0.0'
ET.register_namespace('', NS)


class QualificationError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


def require(condition, message, status='FAIL'):
    if not condition:
        raise QualificationError(status, message)


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def save(path, value):
    Path(path).write_text(json.dumps(value, indent=2, sort_keys=True)+'\n')


def utc():
    return datetime.now(timezone.utc).isoformat()


def capture(command, env=None):
    completed = subprocess.run(command, env=env, text=True, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT)
    require(completed.returncode == 0,
            'Prerequisite command failed: '+shlex.join(map(str, command))+'\n'+completed.stdout,
            'MISSING_PREREQUISITE')
    return completed.stdout


def tree_hash(path):
    digest = hashlib.sha256()
    count = 0
    for file in sorted(Path(path).rglob('*')):
        if file.is_file():
            digest.update(file.relative_to(path).as_posix().encode()+b'\0')
            digest.update(bytes.fromhex(sha(file)))
            count += 1
    return {'sha256': digest.hexdigest(), 'files': count}


def reports(path):
    result = {'totals': dict(tests=0, failures=0, errors=0, skipped=0),
              'testcases': Counter(), 'skips': [], 'problems': [], 'files': []}
    for file in sorted(Path(path).glob('TEST-*.xml')):
        document = ET.parse(file).getroot()
        result['files'].append({'name': file.name, 'sha256': sha(file)})
        for key in result['totals']:
            result['totals'][key] += int(document.get(key, 0))
        for case in document.findall('testcase'):
            name = case.get('classname', '')+'#'+case.get('name', '')
            result['testcases'][name] += 1
            for child in case:
                if child.tag == 'skipped':
                    result['skips'].append({'test': name, **child.attrib})
                elif child.tag in ('failure', 'error', 'flakyFailure', 'rerunFailure', 'flakyError', 'rerunError'):
                    result['problems'].append({'test': name, 'kind': child.tag,
                                               **child.attrib, 'text': child.text})
    return result


def check_tests(result, component, suite='default'):
    expected_count = COUNTS[component] if suite == 'default' else 3
    expected_skips = CORE_SKIPS if component == 'jackson-core' else set()
    if suite == 'typepollution':
        expected_skips = {TYPE_SKIP}
    require(result['totals']['tests'] == expected_count,
            f'{component}/{suite}: expected {expected_count} reported cases; got {result["totals"]}')
    require(sum(result['testcases'].values()) == expected_count,
            f'{component}/{suite}: XML testcase count differs from the pinned suite count')
    require(not result['totals']['failures'] and not result['totals']['errors'] and not result['problems'],
            f'{component}/{suite}: upstream test failures, errors, or retries are present')
    actual = Counter(item['test'] for item in result['skips'])
    require(actual == Counter({name: 1 for name in expected_skips})
            and result['totals']['skipped'] == len(expected_skips),
            f'{component}/{suite}: skip set differs from the explicitly recorded upstream skips')
    result['skip_policy'] = {'status': 'PASS', 'upstream_disabled_tests': sorted(expected_skips)}


def xml(parent, name, value=None):
    node = ET.SubElement(parent, '{'+NS+'}'+name)
    if value is not None:
        node.text = str(value)
    return node


def jar_classes(path):
    classes = set()
    with zipfile.ZipFile(path) as archive:
        for name in archive.namelist():
            name = re.sub(r'^META-INF/versions/\d+/', '', name)
            if name.endswith('.class') and not name.startswith('META-INF/') and name != 'module-info.class':
                classes.add(name[:-6].replace('/', '.'))
    return classes


def same_file_url(actual, expected):
    actual, expected = urlparse(actual), urlparse(expected)
    return (actual.scheme == 'file' and expected.scheme == 'file'
            and unquote(actual.path) == unquote(expected.path))


def inspect_fork(repo, component, out=None, env=None):
    """Read-only source admission shared by the runner and receipt verifier."""
    repo, pin = Path(repo).resolve(), PINS[component]
    require((repo/'pom.xml').is_file(), f'Missing fork POM: {repo}', 'MISSING_PREREQUISITE')
    require(out is None or not Path(out).resolve().is_relative_to(repo),
            '--out must be outside the fork trees', 'MISSING_PREREQUISITE')

    def git(*args):
        return capture(['git', '-C', str(repo), *args], env).strip()

    git('cat-file', '-e', pin+'^{commit}')
    git('merge-base', '--is-ancestor', pin, 'HEAD')
    head = git('rev-parse', 'HEAD')
    tracked = set(git('ls-tree', '-r', '--name-only', '-z', pin).split('\0'))-{''}
    changed = set(git('diff', '--no-ext-diff', '--no-textconv', '--name-only',
                      '-z', pin, 'HEAD', '--').split('\0'))-{''}
    require(not (tracked & changed), f'{component}: upstream-tracked files differ from {pin}',
            'SOURCE_PIN_MISMATCH')
    require(all(name.startswith('m3/m3jdk-jackson/') for name in changed),
            f'{component}: additions outside the generated m3/m3jdk-jackson namespace',
            'SOURCE_PIN_MISMATCH')
    require(not git('status', '--porcelain', '--untracked-files=all'),
            f'{component}: working tree must be clean, including untracked source/build files',
            'SOURCE_TREE_DIRTY')
    return {'path': str(repo), 'fork_head': head, 'upstream_commit': pin,
            'upstream_tree': git('rev-parse', pin+'^{tree}'),
            'original_tracked_files': len(tracked), 'generated_additions': sorted(changed),
            'upstream_files_unchanged': True}


def inspect_origins(folder, component, suite, runtimes, dependencies):
    """Check recorded JVM loads against frozen JAR membership and origin paths.

    A verifier may provide an item's ``verification_path`` to read byte-identical
    artifacts at a later location. Its SHA must still match; ``path`` remains the
    original expected load location in the recorded JVM log.
    """
    names = list(MODULES) if component == 'jackson-databind' else [component]
    require(set(runtimes) == set(names), 'Unexpected runtime module set for '+component)
    production, helper_fdp = {}, {}
    for name in names:
        item = runtimes[name]
        path = Path(item.get('verification_path', item['path']))
        require(sha(path) == item['sha256'], 'Generated JAR changed: '+str(path),
                'RUNTIME_IDENTITY_MISMATCH')
        production.update({clazz: Path(item['path']).as_uri() for clazz in jar_classes(path)})
    for item in dependencies:
        if item['group'] == 'ch.randelshofer' and item['artifact'] == 'fastdoubleparser':
            require(item['version'] == '1.0.90', 'Unexpected upstream test-only FastDoubleParser version')
            path = Path(item.get('verification_path', item['path']))
            require(sha(path) == item['sha256'], 'Test-only FastDoubleParser changed: '+str(path),
                    'INPUT_IDENTITY_MISMATCH')
            helper_fdp.update({clazz: Path(item['path']).as_uri() for clazz in jar_classes(path)})
    origins, helper_origins, redefinitions, bad = {}, {}, [], []
    for logfile in sorted(Path(folder).glob('classload-*.log')):
        for line in logfile.read_text().splitlines():
            match = re.match(r'^\[class,load\] (\S+) source: (.+)$', line)
            if not match:
                continue
            clazz, source = match.groups()
            if clazz not in production and clazz not in helper_fdp:
                continue
            if source == '__VM_RedefineClasses__':
                event = {'class': clazz, 'event': source, 'original_source': origins.get(clazz)}
                redefinitions.append(event)
                if component != 'jackson-databind' or clazz not in REDEFINITIONS[suite] or clazz not in origins:
                    bad.append(event)
                continue
            expected = production.get(clazz, helper_fdp.get(clazz))
            (origins if clazz in production else helper_origins)[clazz] = source
            if not same_file_url(source, expected):
                bad.append({'class': clazz, 'actual': source, 'expected': expected})
    required = {'jackson-annotations': ['com.fasterxml.jackson.annotation.JsonFormat'],
                'jackson-core': ['com.fasterxml.jackson.core.JsonFactory', 'com.fasterxml.jackson.core.JsonParser',
                                'com.fasterxml.jackson.core.internal.shaded.fdp.v2_19_2.JavaDoubleParser'],
                'jackson-databind': ['com.fasterxml.jackson.databind.ObjectMapper',
                                    'com.fasterxml.jackson.core.JsonFactory', 'com.fasterxml.jackson.annotation.JsonFormat']}[component]
    missing = [name for name in required if name not in origins]
    return {'status': 'PASS' if not bad and not missing else 'FAIL',
            'production_classes': origins, 'production_class_count': len(origins),
            'test_only_unshaded_fdp_classes': helper_origins,
            'upstream_test_redefinitions': redefinitions,
            'wrong_origins': bad, 'missing_required_classes': missing}


class Qualification:
    def __init__(self, args):
        self.args = args
        self.out = args.out.resolve()
        require(not self.out.exists() or not any(self.out.iterdir()),
                '--out must be new or empty; existing evidence is never overwritten', 'MISSING_PREREQUISITE')
        self.out.mkdir(parents=True, exist_ok=True)
        self.repos = {'jackson-annotations': args.annotations_root.resolve(),
                      'jackson-core': args.core_root.resolve(),
                      'jackson-databind': args.databind_root.resolve()}
        self.java = args.java_home.resolve()
        self.maven = args.maven.resolve()
        self.cache = args.cache.resolve()
        self.runtime_dir = args.runtime_jars.resolve()
        self.env = dict(os.environ)
        self.env['JAVA_HOME'] = str(self.java)
        self.env['PATH'] = str(self.java/'bin')+os.pathsep+self.env.get('PATH', '')
        self.env['GIT_NO_LAZY_FETCH'] = '1'
        self.result = {'status': 'RUNNING', 'started_utc': utc(), 'lanes': {},
                       'scope': 'Pinned upstream tests on generated Jackson JARs, stock JDK 21 classpath',
                       'm3_vm_and_linked_image_qualification': 'SEPARATE_GATE',
                       'mapper_footprint_test': 'EXCLUDED_BY_UPSTREAM_POM_AND_PRIMARY_SUITE',
                       'upstream_disabled_core_tests': sorted(CORE_SKIPS)}
        self.result['harness'] = {'path': str(Path(__file__).resolve()), 'sha256': sha(__file__)}
        self.result['qualification_tool_sha256'] = self.result['harness']['sha256']
        self.baselines = {}
        self.runtimes = {}
        self.persist()

    def persist(self):
        save(self.out/'RESULT.json', self.result)

    def check_input_custody(self):
        """Bind paths to frozen bytes before and after every generated run."""
        require(sha(self.result['harness']['path']) == self.result['harness']['sha256'],
                'Qualification executable changed during the run', 'INPUT_IDENTITY_MISMATCH')
        receipt = self.result['runtime_receipt']
        require(sha(receipt['path']) == receipt['sha256'],
                'Source-build receipt changed during qualification', 'INPUT_IDENTITY_MISMATCH')
        for item in self.runtimes.values():
            require(sha(item['path']) == item['sha256'],
                    'Generated JAR changed during qualification: '+item['path'], 'RUNTIME_IDENTITY_MISMATCH')
        for (component, suite), baseline in self.baselines.items():
            if suite != 'default':
                continue
            tests = self.out/'upstream'/component/'test-classes'
            require(tree_hash(tests) == baseline['test_classes'],
                    component+': compiled test snapshot changed', 'INPUT_IDENTITY_MISMATCH')
            for path, digest in baseline['test_classpath_artifacts'].items():
                require(sha(path) == digest, 'Upstream baseline dependency changed: '+path,
                        'INPUT_IDENTITY_MISMATCH')
        for component in PINS:
            current = self.check_fork(component)
            require(current['fork_head'] == self.result['forks'][component]['fork_head'],
                    component+': fork HEAD changed during qualification', 'SOURCE_PIN_MISMATCH')

    def baseline_classpath_artifacts(self, folder, component):
        first = next((folder/'surefire-reports').glob('TEST-*.xml'))
        props = {p.get('name'): p.get('value') for p in ET.parse(first).getroot().findall('./properties/property')}
        artifacts = {}
        for value in props['surefire.test.class.path'].split(os.pathsep):
            if not value:
                continue
            path = Path(value).resolve()
            if path in [self.repos[component]/'target/classes', self.repos[component]/'target/test-classes']:
                continue
            require(path.is_file() and path.is_relative_to(self.cache),
                    'Unexpected upstream test dependency path: '+str(path), 'MISSING_PREREQUISITE')
            artifacts[str(path)] = sha(path)
        return artifacts

    def check_fork(self, component):
        return inspect_fork(self.repos[component], component, self.out, self.env)

    def dns(self):
        args = self.args
        require(bool(args.dns_response) == bool(args.dns_provenance),
                'Provide both --dns-response and --dns-provenance, or neither', 'MISSING_PREREQUISITE')
        if not args.dns_response:
            self.result['dns'] = {'mode': 'SYSTEM_RESOLVER', 'fixture': None}
            return
        require(args.dns_response.is_file() and args.dns_provenance.is_file(),
                'Missing recorded DNS response/provenance', 'MISSING_PREREQUISITE')
        response = json.loads(args.dns_response.read_text())
        provenance = json.loads(args.dns_provenance.read_text())
        require(provenance.get('url') == 'https://dns.google/resolve?name=google.com&type=A',
                'Unexpected DNS fixture source URL', 'MISSING_PREREQUISITE')
        require(provenance.get('dns_sha256') == sha(args.dns_response),
                'DNS response hash differs from its provenance', 'MISSING_PREREQUISITE')
        require(bool(provenance.get('retrieved_utc')) and response.get('Status') == 0,
                'DNS fixture needs a successful response and recorded retrieval time', 'MISSING_PREREQUISITE')
        datetime.fromisoformat(provenance['retrieved_utc'].replace('Z', '+00:00'))
        addresses = [str(ipaddress.IPv4Address(answer['data'])) for answer in response.get('Answer', [])
                     if answer.get('name') == 'google.com.' and answer.get('type') == 1]
        require(bool(addresses), 'No verified google.com A records in DNS fixture', 'MISSING_PREREQUISITE')
        folder = self.out/'dns'
        folder.mkdir()
        shutil.copyfile(args.dns_response, folder/'response.json')
        shutil.copyfile(args.dns_provenance, folder/'provenance.json')
        base = Path('/etc/hosts').read_text() if Path('/etc/hosts').is_file() else '127.0.0.1 localhost\n::1 localhost\n'
        (folder/'base-hosts').write_text(base)
        hosts = folder/'hosts'
        hosts.write_text(base+'\n# Recorded upstream-test DNS fixture\n'
                         +''.join(address+' google.com\n' for address in addresses))
        option = '-Djdk.net.hosts.file='+str(hosts)
        self.env['JAVA_TOOL_OPTIONS'] = (self.env.get('JAVA_TOOL_OPTIONS', '')+' '+shlex.quote(option)).strip()
        self.result['dns'] = {'mode': 'RECORDED_DNS_HOSTS_FIXTURE', 'response_sha256': sha(folder/'response.json'),
                              'provenance_sha256': sha(folder/'provenance.json'), 'base_hosts_sha256': sha(folder/'base-hosts'),
                              'hosts_sha256': sha(hosts), 'hosts_path': str(hosts), 'addresses': addresses,
                              'source_url': provenance['url'], 'retrieved_utc': provenance['retrieved_utc'],
                              'jvm_startup_property': option,
                              'application': 'JAVA_TOOL_OPTIONS at JVM startup, not late Surefire properties'}

    def preflight(self):
        require(sys.version_info >= (3, 9), 'Python 3.9 or newer is required', 'MISSING_PREREQUISITE')
        for executable in [self.java/'bin/java', self.java/'bin/jar', self.maven]:
            require(executable.is_file() and os.access(executable, os.X_OK),
                    f'Missing executable: {executable}', 'MISSING_PREREQUISITE')
        if self.args.trust_store:
            require(self.args.trust_store.is_file(), 'Missing --trust-store', 'MISSING_PREREQUISITE')
            option = '-Djavax.net.ssl.trustStore='+str(self.args.trust_store.resolve())
            self.env['MAVEN_OPTS'] = (self.env.get('MAVEN_OPTS', '')+' '+shlex.quote(option)).strip()
        self.cache.mkdir(parents=True, exist_ok=True)
        self.dns()
        java_version = capture([str(self.java/'bin/java'), '-version'], self.env)
        require(re.search(r'version "21(?:\.|\")', java_version),
                'This qualification lock requires JDK 21', 'MISSING_PREREQUISITE')
        self.result['toolchain'] = {'java_home': str(self.java), 'java_version': java_version,
                                    'maven': str(self.maven), 'maven_version': capture([str(self.maven), '-version'], self.env),
                                    'cache': str(self.cache), 'offline': self.args.offline,
                                    'trust_store': str(self.args.trust_store.resolve()) if self.args.trust_store else None}
        self.result['forks'] = {name: self.check_fork(name) for name in PINS}
        empty = self.out/'empty-settings.xml'
        empty.write_text('<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"/>\n')
        self.settings = (self.args.settings or empty).resolve()
        self.global_settings = (self.args.global_settings or empty).resolve()
        for path in [self.settings, self.global_settings]:
            require(path.is_file(), f'Missing Maven settings: {path}', 'MISSING_PREREQUISITE')
        self.result['maven_settings'] = [{'path': str(p), 'sha256': sha(p)} for p in [self.settings, self.global_settings]]
        receipt_path = (self.args.runtime_receipt or self.runtime_dir.parent/'RESULT.json').resolve()
        require(receipt_path.is_file(), 'Generated runtime build RESULT.json is required', 'MISSING_PREREQUISITE')
        receipt = json.loads(receipt_path.read_text())
        require(receipt.get('status') == 'PASS' and receipt.get('source_build') == 'PASS'
                and receipt.get('upstream_version') == '2.19.2',
                'Generated runtime receipt must attest a successful source build of 2.19.2', 'MISSING_PREREQUISITE')
        for component, module in MODULES.items():
            jar = self.runtime_dir/(module+'.jar')
            require(jar.is_file(), f'Missing generated runtime JAR: {jar}', 'MISSING_PREREQUISITE')
            expected = receipt.get('jars', {}).get(module, {}).get('sha256')
            require(sha(jar) == expected, f'{module}: JAR hash differs from source-build receipt', 'RUNTIME_IDENTITY_MISMATCH')
            descriptor = capture([str(self.java/'bin/jar'), '--describe-module', '--file', str(jar), '--release', '21'], self.env)
            require(re.search(r'^'+re.escape(module)+r'@2\.19\.2(?:\s|$)', descriptor, re.M),
                    f'{module}: missing or unexpected explicit module descriptor', 'RUNTIME_IDENTITY_MISMATCH')
            self.runtimes[component] = {'module': module, 'path': str(jar), 'sha256': expected,
                                        'module_descriptor': descriptor}
        self.result['runtime_receipt'] = {'path': str(receipt_path), 'sha256': sha(receipt_path)}
        self.result['runtime_jars'] = self.runtimes
        self.persist()

    def mvn(self, pom, arguments, folder, source_reports):
        command = [str(self.maven), '-B', '-ntp', '-C', '-s', str(self.settings), '-gs', str(self.global_settings),
                   '-Dmaven.repo.local='+str(self.cache), '-f', str(pom), '-DskipTests=false',
                   '-Dmaven.test.skip=false', '-Dmaven.test.failure.ignore=false', '-Dsurefire.rerunFailingTestsCount=0']
        if self.args.offline:
            command.append('-o')
        command.extend(arguments)
        started = time.time()
        with (folder/'maven.log').open('w') as stream:
            process = subprocess.run(command, cwd=pom.parent, env=self.env, stdout=stream, stderr=subprocess.STDOUT)
        target_reports = folder/'surefire-reports'
        if source_reports.exists() and source_reports.resolve() != target_reports.resolve():
            shutil.copytree(source_reports, target_reports)
        result = reports(target_reports)
        result.update({'command': command, 'exit_code': process.returncode, 'elapsed_seconds': round(time.time()-started, 3),
                       'maven_log_sha256': sha(folder/'maven.log')})
        return result

    def record(self, lane, component, result):
        folder = self.out/lane/component
        save(folder/'RESULT.json', result)
        self.result['lanes'][lane+'/'+component] = {'receipt': str(folder/'RESULT.json'),
            'sha256': sha(folder/'RESULT.json'), 'status': result.get('status', 'FAIL'), 'totals': result['totals']}
        self.persist()

    def check_command(self, result, folder):
        if result['exit_code'] == 0:
            return
        text = (folder/'maven.log').read_text()
        problems = json.dumps(result['problems'])
        missing = ('Could not self-attach to current VM' in problems or 'UnknownHostException' in problems
                   or 'Cannot access central' in text or 'has not been downloaded from it before' in text)
        raise QualificationError('MISSING_PREREQUISITE' if missing else 'FAIL',
                                 'Maven failed; unchanged failure evidence is in '+str(folder))

    def baseline(self, component, suite='default'):
        lane = 'upstream' if suite == 'default' else 'upstream-typepollution'
        folder = self.out/lane/component
        folder.mkdir(parents=True)
        repo = self.repos[component]
        if suite == 'default':
            arguments = ['clean', 'install']
            if component == 'jackson-annotations':
                arguments += ['-Djavac.src.version=1.8', '-Djavac.target.version=1.8',
                              '-Dmaven.compiler.source=1.8', '-Dmaven.compiler.target=1.8']
        else:
            # This is the existing pinned POM's declared isolated execution. No
            # test selector or source is invented, and no compilation is rerun.
            previous = repo/'target/surefire-reports'
            if previous.exists():
                previous.rename(folder/'previous-live-reports')
            arguments = ['-Pjava17', 'org.apache.maven.plugins:maven-surefire-plugin:3.5.3:test@type-pollution-test']
        print('START '+lane+'/'+component, flush=True)
        result = self.mvn(repo/'pom.xml', arguments, folder, repo/'target/surefire-reports')
        result.update({'status': 'FAIL', 'suite': suite, 'runtime': 'Unchanged upstream source compiled in fork target directories',
                       'fork': self.result['forks'][component]})
        if component == 'jackson-annotations':
            result['compilation_override'] = 'Java 8 source/target properties; pinned annotations POM asks for unsupported Java 6 on JDK 21'
        self.record(lane, component, result)
        self.check_command(result, folder)
        check_tests(result, component, suite)
        self.check_fork(component)
        if suite == 'default':
            test_classes = repo/'target/test-classes'
            require(test_classes.is_dir(), 'Missing compiled upstream test classes', 'MISSING_PREREQUISITE')
            shutil.copytree(test_classes, folder/'test-classes')
            result['test_classes'] = tree_hash(folder/'test-classes')
            result['test_classpath_artifacts'] = self.baseline_classpath_artifacts(folder, component)
            artifact = repo/'target'/(component+'-2.19.2.jar')
            require(artifact.is_file(), 'Full Maven install did not produce '+str(artifact))
            result['upstream_jar'] = {'path': str(artifact), 'sha256': sha(artifact)}
        result['status'] = 'PASS'
        self.record(lane, component, result)
        self.baselines[(component, suite)] = result
        print('PASS '+lane+'/'+component+' '+json.dumps(result['totals']), flush=True)

    def replay(self, component, suite='default'):
        self.check_input_custody()
        lane = 'generated' if suite == 'default' else 'generated-typepollution'
        folder = self.out/lane/component
        folder.mkdir(parents=True)
        baseline_dir = self.out/'upstream'/component
        test_classes = baseline_dir/'test-classes'
        require(tree_hash(test_classes) == self.baselines[(component, 'default')]['test_classes'],
                'Compiled upstream test snapshot changed before replay')
        first = next((baseline_dir/'surefire-reports').glob('TEST-*.xml'))
        properties = {p.get('name'): p.get('value') for p in ET.parse(first).getroot().findall('./properties/property')}
        paths = properties['surefire.test.class.path'].split(os.pathsep)
        dependencies, removed = [], []
        for value in paths:
            if not value:
                continue
            path = Path(value).resolve()
            if path in [self.repos[component]/'target/classes', self.repos[component]/'target/test-classes']:
                removed.append(str(path))
                continue
            require(path.is_file() and path.is_relative_to(self.cache),
                    'Unexpected upstream test classpath entry: '+str(path))
            expected_digest = self.baselines[(component, 'default')]['test_classpath_artifacts'].get(str(path))
            require(expected_digest is not None and sha(path) == expected_digest,
                    'Dependency differs from successful upstream baseline: '+str(path), 'INPUT_IDENTITY_MISMATCH')
            parts = path.relative_to(self.cache).parts
            group, artifact, version = '.'.join(parts[:-3]), parts[-3], parts[-2]
            if group.startswith('com.fasterxml.jackson'):
                removed.append(str(path))
                continue
            dependencies.append({'group': group, 'artifact': artifact, 'version': version,
                                 'path': str(path), 'sha256': sha(path)})
        names = list(MODULES) if component == 'jackson-databind' else [component]
        for name in names:
            item = self.runtimes[name]
            require(sha(item['path']) == item['sha256'], 'Generated JAR changed during qualification', 'RUNTIME_IDENTITY_MISMATCH')
            dependencies.append({'group': 'com.fasterxml.jackson.core', 'artifact': name,
                                 'version': '2.19.2-m3-source1', 'path': item['path'], 'sha256': item['sha256']})
        self.replay_pom(folder, component, suite, test_classes, dependencies)
        print('START '+lane+'/'+component, flush=True)
        result = self.mvn(folder/'pom.xml', ['org.apache.maven.plugins:maven-surefire-plugin:3.5.3:test'],
                          folder, folder/'surefire-reports')
        result.update({'status': 'FAIL', 'suite': suite, 'runtime': 'Exact generated Jackson JARs on JDK 21 classpath',
                       'runtime_jars': {name: self.runtimes[name] for name in names}, 'dependencies': dependencies,
                       'removed_upstream_runtime_entries': removed,
                       'test_classes': self.baselines[(component, 'default')]['test_classes']})
        self.record(lane, component, result)
        self.check_command(result, folder)
        check_tests(result, component, suite)
        expected_cases = self.baselines[(component, suite)]['testcases']
        result['testcase_selection_matches_upstream'] = result['testcases'] == expected_cases
        require(result['testcase_selection_matches_upstream'], 'Generated replay changed the upstream testcase multiset')
        classload_logs = sorted(folder.glob('classload-*.log'))
        result['classload_logs'] = [{'name': logfile.name, 'sha256': sha(logfile)} for logfile in classload_logs]
        result['origin_check'] = inspect_origins(folder, component, suite, result['runtime_jars'], dependencies)
        self.record(lane, component, result)
        require(result['origin_check']['status'] == 'PASS', 'Generated Jackson/FDP class origins failed verification')
        self.check_input_custody()
        result['post_run_input_custody'] = 'PASS'
        result['status'] = 'PASS'
        self.record(lane, component, result)
        print('PASS '+lane+'/'+component+' '+json.dumps(result['totals']), flush=True)

    def replay_pom(self, folder, component, suite, tests, dependencies):
        pom = ET.Element('{'+NS+'}project')
        xml(pom, 'modelVersion', '4.0.0')
        xml(pom, 'groupId', 'local.m3.qualification')
        xml(pom, 'artifactId', component+'-generated-tests')
        xml(pom, 'version', '1')
        props = xml(pom, 'properties')
        dep_root = xml(pom, 'dependencies')
        for index, item in enumerate(dependencies):
            key = 'qualified.dependency.'+str(index)
            xml(props, key, item['path'])
            node = xml(dep_root, 'dependency')
            for tag, field in [('groupId', 'group'), ('artifactId', 'artifact'), ('version', 'version')]:
                xml(node, tag, item[field])
            xml(node, 'scope', 'system')
            xml(node, 'systemPath', '${'+key+'}')
        build = xml(pom, 'build')
        xml(build, 'directory', folder/'target')
        plugin = xml(xml(build, 'plugins'), 'plugin')
        xml(plugin, 'groupId', 'org.apache.maven.plugins')
        xml(plugin, 'artifactId', 'maven-surefire-plugin')
        xml(plugin, 'version', '3.5.3')
        config = xml(plugin, 'configuration')
        (folder/'empty-main-classes').mkdir()
        for name, value in [('classesDirectory', folder/'empty-main-classes'), ('testClassesDirectory', tests),
                            ('reportsDirectory', folder/'surefire-reports'), ('workingDirectory', self.repos[component]),
                            ('useModulePath', 'false'), ('failIfNoTests', 'true'), ('testFailureIgnore', 'false'),
                            ('rerunFailingTestsCount', '0'), ('redirectTestOutputToFile', 'true'), ('forkCount', '1'),
                            ('reuseForks', 'true'), ('jvm', self.java/'bin/java')]:
            xml(config, name, value)
        argline = shlex.quote('-Xlog:class+load=info:file='+str(folder/'classload-%p.log')+':tags')
        if component == 'jackson-databind':
            argline += ' --add-opens=java.base/java.util=ALL-UNNAMED'
            xml(config, 'test', 'com.fasterxml.jackson.databind.'+(
                'PrimarySuite' if suite == 'default' else 'typepollution.TypePollutionSuite'))
            xml(config, 'threadCount', '4' if suite == 'default' else '1')
            xml(config, 'parallel', 'classes')
        xml(config, 'argLine', argline)
        xml(xml(config, 'systemPropertyVariables'), 'basedir', self.repos[component])
        ET.indent(pom, space='  ')
        ET.ElementTree(pom).write(folder/'pom.xml', encoding='utf-8', xml_declaration=True)

    def run(self):
        try:
            self.preflight()
            for component in PINS:
                self.baseline(component)
            for component in PINS:
                self.replay(component)
            self.baseline('jackson-databind', 'typepollution')
            self.replay('jackson-databind', 'typepollution')
            self.check_input_custody()
            self.result['final_input_custody'] = 'PASS'
            self.result['status'] = 'PASS'
            self.result['main_suite_totals_per_lane'] = {'tests': 5131, 'passed': 5129, 'skipped': 2, 'failures': 0, 'errors': 0}
            self.result['additional_typepollution_per_lane'] = {'tests': 3, 'passed': 2, 'skipped': 1, 'failures': 0, 'errors': 0}
        except QualificationError as error:
            self.result['status'] = error.status
            self.result['reason'] = str(error)
        except (OSError, ValueError, KeyError, ET.ParseError) as error:
            self.result['status'] = 'MISSING_PREREQUISITE'
            self.result['reason'] = type(error).__name__+': '+str(error)
        except KeyboardInterrupt:
            self.result['status'] = 'INTERRUPTED'
            self.result['reason'] = 'Qualification interrupted; completed receipts retained'
        finally:
            self.result['finished_utc'] = utc()
            self.persist()
        print('JACKSON_UPSTREAM_QUALIFICATION_'+self.result['status']+' '+str(self.out/'RESULT.json'), flush=True)
        return 0 if self.result['status'] == 'PASS' else 2


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ['annotations-root', 'core-root', 'databind-root', 'runtime-jars', 'java-home', 'maven', 'cache', 'out']:
        parser.add_argument('--'+flag, type=Path, required=True)
    for flag in ['runtime-receipt', 'settings', 'global-settings', 'trust-store', 'dns-response', 'dns-provenance']:
        parser.add_argument('--'+flag, type=Path)
    parser.add_argument('--offline', action='store_true', help='Require the complete Maven build/test dependency cache')
    args = parser.parse_args()
    try:
        runner = Qualification(args)
    except (QualificationError, OSError) as error:
        print('JACKSON_UPSTREAM_QUALIFICATION_MISSING_PREREQUISITE '+str(error), file=sys.stderr)
        return 2
    return runner.run()


if __name__ == '__main__':
    raise SystemExit(main())
