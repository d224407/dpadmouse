# Keep line numbers so release stack traces are readable (use with build/outputs/mapping/release/mapping.txt)
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*
-renamesourcefileattribute SourceFile

# Release build: NO logging. Strip every DLog and android.util.Log call.
-assumenosideeffects class app.dpadmouse.DLog {
    public *** v(...);
    public *** d(...);
    public *** i(...);
    public *** w(...);
    public *** e(...);
}
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}
