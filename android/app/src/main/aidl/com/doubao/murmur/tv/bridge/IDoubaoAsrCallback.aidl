// Contract version: 1 (frozen).
//
// Reverse callback of IDoubaoAsrBridge. The patched Gboard AsrClient implements a hand-written
// Binder proxy that hardcodes DESCRIPTOR = "com.doubao.murmur.tv.bridge.IDoubaoAsrCallback" and the
// transaction ids assigned by AIDL (declaration order, starting at 1). Never reorder or rename.
//
// transaction 1: void onReady()
// transaction 2: void onPartial(String text)
// transaction 3: void onFinal(String text)
// transaction 4: void onError(int code, String message)
//
// Calls are serialized by the service (one at a time), but they arrive on a Binder thread in the
// caller's process, never on its main thread.
//
// Error codes (see BridgeErrorCodes in the service module, kept out of the AIDL so the frozen
// signature above is not extended):
//   1 NOT_SIGNED_IN      2 BAD_AUDIO_FORMAT   3 EMPTY_AUDIO        4 ENGINE_FAILURE
//   5 NO_SPEECH          6 CALLBACK_FAILED
package com.doubao.murmur.tv.bridge;

interface IDoubaoAsrCallback {
    void onReady();
    void onPartial(String text);
    void onFinal(String text);
    void onError(int code, String message);
}
