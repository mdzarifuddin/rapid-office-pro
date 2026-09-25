# pdfium's JNI layer looks these up by name from native code, so R8 must not rename or remove them.
-keep class io.legere.pdfiumandroid.** { *; }
-keep class io.legere.pdfiumandroid.core.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Views inflated from XML are constructed reflectively.
-keep class top.teamaos.pdfreader.view.** { *; }

# Keep line numbers so a stack trace from the phone is still readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
