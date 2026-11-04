// ForeignCheck loads this with System.loadLibrary: a JNI_OnLoad asking for a
// JNI version no JVM supports, which HotSpot refuses and champaca refuses as
// any library with a JNI_OnLoad.
int JNI_OnLoad(void *vm, void *reserved) { return 0x7fffffff; }
