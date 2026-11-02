<!--
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# DOOP serializer-cache key/value source receipt

PropertySerializerMap replaces its private per-entry TypeAndSerializer carriers
with immutable parent-owned Class keys and ValueSerializer values arrays.
Public APIs and fresh SerializerAndMapResult objects remain unchanged. Lookup
preserves the first three positions followed by reverse search of remaining
positions, including duplicate and null keys/serializers. Prior snapshots,
sibling growth, the eight-entry freeze limit and root-map reset are preserved.

Canonical recipe and qualification: [commit 905af4762e79](https://github.com/hsoliwal/com.synexia/commit/905af4762e794c2ad36cbf418f14c3ff29323df1).
Dataset: `doop-key-value-jackson-databind-20261102`. Both Java files below are
actual verified SDK Result outputs. Qualified original input:
`4999888a087d494659f30a8a44669746324effa7`. Receiving parent:
`33550e923d62975fb500a0720c3396f1a1c516f1`. The exact target preimage is unchanged on this existing
upstream branch, preserving its 19 upstream commits.

## Executed verification

The clean native candidate passes 57 selected tests: 49 unchanged original
serialization cases and eight new cases, with zero failures, errors or skips.
The original implementation passes 56 and fails only the intended retained-carrier
assertion. The fresh receiving-base build compiles 578 production/generated and
830 test sources and repeats the same 57 passes. No POM, dependency declaration,
module or build configuration changed. Runtime is Java 21; separate owner source
compilation passed at release 17, which is not a full Java 17 runtime claim.

Independent comparison matched 2,749,304 observations and exact public/protected
owner/result descriptors. In the original-pin clean builds, 911 other main
classfiles are byte-identical, five owner-family classes changed, and the old
TypeAndSerializer classfile is absent. All six remaining owner-family classes
are byte-identical between the original-pin and receiving-pin candidates.

## Measured storage tradeoff

On the measured JVM, the current eight-entry map changes from 10 objects/264 bytes
to 3 objects/120 bytes, and per-entry carriers from eight to zero. Keeping every
intermediate map of sizes 3 through 8 changes 20 objects/576 bytes to 18 objects/624
bytes because the former pair objects were shared. Semantic class and serializer
payload objects are excluded. No universal throughput or allocation gain is claimed.

## Exact Java output blobs

| Path | Git blob |
|---|---|
| `src/main/java/tools/jackson/databind/ser/impl/PropertySerializerMap.java` | `3db929dc2dae6f0c31828bac4dcf1a71a40afc62` |
| `src/test/java/tools/jackson/databind/ser/PropertySerializerMapArrayTest.java` | `5b4617de84cfa63b291b5b31c0942f35e96eae33` |

## Acceptance scope

This is a selected native serialization suite and finite private storage owner.
The full repository suite and release/profile gates remain separate. Jackson Core's
protected Bucket representation is a distinct compatibility hold and is unchanged.
No whole-repository or fleet-wide conversion is claimed.
