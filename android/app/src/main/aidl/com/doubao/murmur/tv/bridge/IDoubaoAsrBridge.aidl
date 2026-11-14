// Contract version: 1 (frozen).
//
// Consumed by patched Gboard (com.google.android.inputmethod.latin), whose AsrClient uses a
// hand-written Binder proxy: it hardcodes DESCRIPTOR = "com.doubao.murmur.tv.bridge.IDoubaoAsrBridge"
// and the transaction ids assigned by AIDL (declaration order, starting at 1). Never reorder,
// rename, insert or delete a method here; add a new interface (v2) instead.
//
// transaction 1: boolean isSignedIn()
// transaction 2: boolean startRecognition(int sampleRate, int channels, IDoubaoAsrCallback callback)
// transaction 3: void pushAudio(in byte[] pcm)
// transaction 4: void finishAudio()
// transaction 5: void cancel()
// transaction 6: void openLoginActivity()
//
// Audio contract: pushAudio carries headerless PCM, 16 bit little endian. The official stream is
// 16 kHz mono; other sample rates / channel counts are converted inside the service, but the caller
// should send 16 kHz mono to avoid the conversion cost. A single chunk must not exceed 1 MiB.
package com.doubao.murmur.tv.bridge;

import com.doubao.murmur.tv.bridge.IDoubaoAsrCallback;

interface IDoubaoAsrBridge {
    boolean isSignedIn();
    boolean startRecognition(int sampleRate, int channels, IDoubaoAsrCallback callback);
    void pushAudio(in byte[] pcm);
    void finishAudio();
    void cancel();
    void openLoginActivity();
}
