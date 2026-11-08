# Third-Party Notices

Keel is distributed under the MIT License. This file records the third-party
components that Keel depends on, together with their licenses and the way they
are used.

Keel does not vendor third-party source code. Runtime dependencies are resolved
from Maven repositories during the build. The Kotlin/Native toolchain is
downloaded by Gradle and is not part of this repository.

## Build and runtime dependencies

| Component | Version | License | Usage |
| --- | --- | --- | --- |
| Kotlin | 2.4.20 | Apache License 2.0 | Language, standard library, and Gradle toolchain |
| Kotlin/Native | 2.4.20 | Apache License 2.0 | Native compiler, runtime, and platform libraries |
| kotlinx.coroutines | 1.10.2 | Apache License 2.0 | Coroutines in the host, plugin API, and HTTP bridge |
| kotlinx.serialization | 1.11.0 | Apache License 2.0 | JSON and CBOR serialization for DTOs and wire payloads |
| Ktor | 3.4.2 | Apache License 2.0 | HTTP server engine used by `keel-host` |
| Kotlin Symbol Processing | 2.3.12 | Apache License 2.0 | Build-time code generation in `keel-plugin-ksp` and `keel-router-ksp` |
| Gradle | 9.5.1 | Apache License 2.0 | Build system, wrapper, and Gradle plugin API |
| Apache Ant | Bundled with Gradle | Apache License 2.0 | Host detection utilities used by the classpath Gradle plugin |

Copyright notices:

- Kotlin, kotlinx.coroutines, kotlinx.serialization, and Ktor: JetBrains s.r.o. and contributors.
- Kotlin Symbol Processing: Google LLC.
- Gradle: Gradle, Inc.
- Apache Ant: The Apache Software Foundation.

## Toolchain components

The Kotlin/Native distribution used to build Keel includes additional
third-party components. The following components may be linked into produced
binaries or used during compilation:

| Component | License | Usage |
| --- | --- | --- |
| LLVM | Apache License 2.0 with LLVM Exceptions | Native code generation and optimization |
| mingw-w64 runtime and headers | Multiple licenses; see the Kotlin/Native distribution | Windows target runtime and system bindings |
| Platform C runtimes | Per-platform licenses | Linux and Windows process runtime |

The authoritative license texts and notices for these components are included
in the Kotlin/Native distribution downloaded into the local `~/.konan`
directory. Consult that distribution when redistributing binaries built with
Kotlin/Native.

## Transitive dependencies

Gradle and Ktor resolve additional transitive dependencies at build time. These
include components licensed under Apache License 2.0, MIT, and BSD-style
licenses. The resolved graph is the authoritative list for a given build. Run
the following command in the relevant build directory to inspect it:

```powershell
gradlew dependencies
```

## License texts

- Apache License 2.0: <https://www.apache.org/licenses/LICENSE-2.0>
- MIT License: see [LICENSE](LICENSE)

## Redistribution

When distributing a Keel-based host, plugin package, or single-file launcher,
include this file together with the upstream notices required by the components
that are linked into the distributed binary. The Kotlin/Native toolchain and the
Gradle dependency graph determine the final set of linked components.
