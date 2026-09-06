# Called from aribcaption_jni.cpp via FindClass/GetMethodID.
-keep class net.rokoucha.visiomata.playback.libaribcaption.NativeCaption {
    <init>(long, int, int, int[][], int[], int[], int[], int[]);
}
