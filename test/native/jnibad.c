// Copyright (C) 2026  champaca contributors
// Derived from OpenLDK, Copyright (C) 2024, 2025, 2026
//   Anthony Green <green@moxielogic.com>
//
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See COPYING and COPYING.CLASSPATH-EXCEPTION.

// ForeignCheck loads this with System.loadLibrary: a JNI_OnLoad asking for a
// JNI version no JVM supports, which HotSpot refuses and champaca refuses as
// any library with a JNI_OnLoad.
int JNI_OnLoad(void *vm, void *reserved) { return 0x7fffffff; }
