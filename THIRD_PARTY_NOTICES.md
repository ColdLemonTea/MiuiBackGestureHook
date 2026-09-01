# Third-party notices

## DexKit 2.2.0

This project distributes the unmodified `org.luckypray:dexkit:2.2.0` Android library.
DexKit's Java/Kotlin portions are licensed under Apache License 2.0, while its native
`Core/` implementation is licensed under GNU LGPL 3.0. The application code in this
repository remains licensed under Apache License 2.0.

- Project and corresponding source: <https://github.com/LuckyPray/DexKit/tree/2.2.0>
- DexKit licensing details: <https://github.com/LuckyPray/DexKit#license>
- GNU LGPL 3.0: <https://www.gnu.org/licenses/lgpl-3.0.html>
- GNU GPL 3.0 incorporated by the LGPL: <https://www.gnu.org/licenses/gpl-3.0.html>

The library is not modified. This repository contains the application source and build
instructions needed to rebuild the APK against a compatible modified DexKit library.
The Apache License applied to the application does not prohibit reverse engineering for
debugging modifications to DexKit.

## LSPosed hook-page protection

The general-purpose LSPosed architecture used to protect hooked pages from memory cleanup
was adapted from the implementation tested in the Dr-TSNG's MiCTS fork repository. This project applies
that architecture to Xiaomi's `MADV_DONTNEED` cleanup behavior.

- Reference implementation and test repository: <https://github.com/Dr-TSNG/MiCTS>
