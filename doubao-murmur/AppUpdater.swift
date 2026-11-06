import AppKit

@MainActor
final class AppUpdater: NSObject {
    private var downloadTask: URLSessionDownloadTask?
    private var session: URLSession?
    private var progressWindow: NSWindow?
    private var progressBar: NSProgressIndicator?
    private var statusLabel: NSTextField?
    private var continuation: CheckedContinuation<URL, Error>?

    func downloadAndInstall(update: UpdateChecker.UpdateInfo) {
        showProgressWindow(version: update.version)
        Task {
            do {
                let zipURL = try await download(url: update.assetURL)
                updateStatus("正在安装...")
                try installAndRelaunch(zipPath: zipURL)
            } catch is CancellationError {
                closeProgressWindow()
            } catch {
                closeProgressWindow()
                let alert = NSAlert()
                alert.messageText = "更新失败"
                alert.informativeText = error.localizedDescription
                alert.alertStyle = .warning
                alert.addButton(withTitle: "好的")
                alert.runModal()
            }
        }
    }

    // MARK: - Download

    private func download(url: URL) async throws -> URL {
        return try await withCheckedThrowingContinuation { cont in
            self.continuation = cont
            let config = URLSessionConfiguration.ephemeral
            let session = URLSession(configuration: config, delegate: self, delegateQueue: .main)
            self.session = session
            let task = session.downloadTask(with: url)
            self.downloadTask = task
            task.resume()
        }
    }

    // MARK: - Install

    private func installAndRelaunch(zipPath: URL) throws {
        let appBundlePath = Bundle.main.bundlePath
        let fm = FileManager.default
        let tempDir = fm.temporaryDirectory.appendingPathComponent("doubao-murmur-update-\(UUID().uuidString)")
        try fm.createDirectory(at: tempDir, withIntermediateDirectories: true)

        // Unzip
        let unzip = Process()
        unzip.executableURL = URL(fileURLWithPath: "/usr/bin/unzip")
        unzip.arguments = ["-o", zipPath.path, "-d", tempDir.path]
        try unzip.run()
        unzip.waitUntilExit()
        guard unzip.terminationStatus == 0 else {
            throw UpdateError.unzipFailed
        }

        // Find the .app inside the unzipped directory
        let contents = try fm.contentsOfDirectory(at: tempDir, includingPropertiesForKeys: nil)
        guard let newApp = contents.first(where: { $0.pathExtension == "app" }) else {
            throw UpdateError.appNotFound
        }

        // Write a shell script that waits for us to quit, then replaces the app and relaunches
        let pid = ProcessInfo.processInfo.processIdentifier
        let script = """
        #!/bin/bash
        # Wait for the current app to exit
        while kill -0 \(pid) 2>/dev/null; do sleep 0.1; done
        # Replace
        rm -rf "\(appBundlePath)"
        mv "\(newApp.path)" "\(appBundlePath)"
        # Clean up
        rm -rf "\(tempDir.path)"
        rm -f "\(zipPath.path)"
        # Relaunch
        open "\(appBundlePath)"
        """

        let scriptPath = tempDir.appendingPathComponent("update.sh")
        try script.write(to: scriptPath, atomically: true, encoding: .utf8)
        try fm.setAttributes([.posixPermissions: 0o755], ofItemAtPath: scriptPath.path)

        let launcher = Process()
        launcher.executableURL = URL(fileURLWithPath: "/bin/bash")
        launcher.arguments = [scriptPath.path]
        try launcher.run()

        // Quit the app so the script can replace it
        NSApp.terminate(nil)
    }

    // MARK: - Progress UI

    private func showProgressWindow(version: String) {
        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 340, height: 100),
            styleMask: [.titled],
            backing: .buffered,
            defer: false
        )
        window.title = "更新 Doubao Murmur"
        window.center()
        window.isReleasedWhenClosed = false

        let contentView = NSView(frame: window.contentView!.bounds)

        let label = NSTextField(labelWithString: "正在下载 v\(version)...")
        label.frame = NSRect(x: 20, y: 55, width: 300, height: 20)
        label.font = .systemFont(ofSize: 13)
        contentView.addSubview(label)
        self.statusLabel = label

        let progress = NSProgressIndicator(frame: NSRect(x: 20, y: 30, width: 300, height: 20))
        progress.style = .bar
        progress.isIndeterminate = false
        progress.minValue = 0
        progress.maxValue = 100
        progress.doubleValue = 0
        contentView.addSubview(progress)
        self.progressBar = progress

        let cancelBtn = NSButton(title: "取消", target: self, action: #selector(cancelDownload))
        cancelBtn.frame = NSRect(x: 250, y: 2, width: 70, height: 24)
        cancelBtn.bezelStyle = .rounded
        contentView.addSubview(cancelBtn)

        window.contentView = contentView
        window.level = .floating
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        self.progressWindow = window
    }

    private func updateStatus(_ text: String) {
        statusLabel?.stringValue = text
    }

    private func closeProgressWindow() {
        progressWindow?.close()
        progressWindow = nil
    }

    @objc private func cancelDownload() {
        downloadTask?.cancel()
        session?.invalidateAndCancel()
        closeProgressWindow()
        continuation?.resume(throwing: CancellationError())
        continuation = nil
    }

    // MARK: - Error

    enum UpdateError: LocalizedError {
        case unzipFailed
        case appNotFound

        var errorDescription: String? {
            switch self {
            case .unzipFailed: return "解压更新包失败。"
            case .appNotFound: return "更新包中未找到应用程序。"
            }
        }
    }
}

// MARK: - URLSessionDownloadDelegate

extension AppUpdater: URLSessionDownloadDelegate {
    nonisolated func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                                didWriteData bytesWritten: Int64, totalBytesWritten: Int64,
                                totalBytesExpectedToWrite: Int64) {
        let percent = totalBytesExpectedToWrite > 0
            ? Double(totalBytesWritten) / Double(totalBytesExpectedToWrite) * 100
            : 0
        let writtenMB = Double(totalBytesWritten) / 1_048_576
        let totalMB = Double(totalBytesExpectedToWrite) / 1_048_576
        MainActor.assumeIsolated {
            progressBar?.doubleValue = percent
            statusLabel?.stringValue = String(format: "正在下载... %.1f / %.1f MB", writtenMB, totalMB)
        }
    }

    nonisolated func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                                didFinishDownloadingTo location: URL) {
        // Move the file to a stable temp path before the continuation resumes
        let dest = FileManager.default.temporaryDirectory.appendingPathComponent("doubao-murmur-update.zip")
        try? FileManager.default.removeItem(at: dest)
        try? FileManager.default.moveItem(at: location, to: dest)
        MainActor.assumeIsolated {
            continuation?.resume(returning: dest)
            continuation = nil
        }
    }

    nonisolated func urlSession(_ session: URLSession, task: URLSessionTask,
                                didCompleteWithError error: Error?) {
        guard let error = error else { return }
        MainActor.assumeIsolated {
            continuation?.resume(throwing: error)
            continuation = nil
        }
    }
}
