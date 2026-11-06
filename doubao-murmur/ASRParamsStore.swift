import Foundation

/// Persists DoubaoASRParams to a local JSON file so the app can operate
/// without keeping WKWebView alive after initial login.
struct ASRParamsStore {
    private static let fileName = "asr_params.json"

    private static var fileURL: URL {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let dir = appSupport.appendingPathComponent("com.doubao.murmur")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent(fileName)
    }

    static func save(_ params: DoubaoASRParams) {
        do {
            let data = try JSONEncoder().encode(params)
            try data.write(to: fileURL, options: .atomic)
            print("[ASRParamsStore] ✅ Saved ASR params to \(fileURL.path)")
        } catch {
            print("[ASRParamsStore] ❌ Failed to save: \(error)")
        }
    }

    static func load() -> DoubaoASRParams? {
        guard let data = try? Data(contentsOf: fileURL),
              let params = try? JSONDecoder().decode(DoubaoASRParams.self, from: data) else {
            return nil
        }
        print("[ASRParamsStore] ✅ Loaded saved ASR params")
        return params
    }

    static func clear() {
        try? FileManager.default.removeItem(at: fileURL)
        print("[ASRParamsStore] Cleared saved params")
    }

    static var hasSavedParams: Bool {
        FileManager.default.fileExists(atPath: fileURL.path)
    }
}
