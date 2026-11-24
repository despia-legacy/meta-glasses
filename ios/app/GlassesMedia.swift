#if DESPIA_NX_ENABLED
import AVFoundation
import CoreMedia
import CoreVideo
import Foundation
import UIKit

/// Clips and the Mock Device Kit's media.
enum GlassesMedia {
    /// JPEG quality of the live preview: small frames for a smooth stream.
    static let previewQuality: CGFloat = 0.8
    /// JPEG quality of a saved photo.
    static let photoQuality: CGFloat = 0.92

    /// Mock feed and photo size: the medium stream size, portrait (Meta, "Camera Streaming").
    static let mockWidth = 504
    static let mockHeight = 896
    static let mockFPS: Int32 = 24
    /// 30 s: on the simulator (24 Nov) DAT 1.1.0's iOS mock ended a stream with a stream error about
    /// 6 s into a 3 s feed; a feed longer than any test stream keeps the mock from reaching that.
    static let mockFrames = 720

    /// Writes data next to url and moves it into place, so a reader never sees half a file.
    static func write(_ data: Data, to url: URL) throws {
        let part = url.appendingPathExtension("part")
        try data.write(to: part, options: .atomic)
        if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
        try FileManager.default.moveItem(at: part, to: url)
    }

    /// The mock test pattern, frame n: left half red, right half blue, and a white bar 32 rows
    /// high moving down. The harness reads the colours back from /frame.jpg and the saved media.
    static func drawPattern(_ ctx: CGContext, width: Int, height: Int, frame n: Int) {
        ctx.setFillColor(UIColor.red.cgColor)
        ctx.fill(CGRect(x: 0, y: 0, width: width / 2, height: height))
        ctx.setFillColor(UIColor.blue.cgColor)
        ctx.fill(CGRect(x: width / 2, y: 0, width: width - width / 2, height: height))
        let bar = (n * 12) % height
        ctx.setFillColor(UIColor.white.cgColor)
        // CGContext's origin is bottom-left; the bar moves down from the top.
        ctx.fill(CGRect(x: 0, y: height - bar - 32, width: width, height: 32))
    }

    /// The mock photo: the same red and blue halves, as a JPEG.
    static func mockPhoto() -> Data? {
        let r = UIGraphicsImageRenderer(size: CGSize(width: mockWidth, height: mockHeight),
                                        format: { let f = UIGraphicsImageRendererFormat(); f.scale = 1; return f }())
        let image = r.image { c in
            c.cgContext.setFillColor(UIColor.red.cgColor)
            c.cgContext.fill(CGRect(x: 0, y: 0, width: mockWidth / 2, height: mockHeight))
            c.cgContext.setFillColor(UIColor.blue.cgColor)
            c.cgContext.fill(CGRect(x: mockWidth / 2, y: 0, width: mockWidth - mockWidth / 2, height: mockHeight))
        }
        return image.jpegData(compressionQuality: photoQuality)
    }

    /// Writes the mock camera feed: mockFrames frames of the test pattern as H.265 (the format
    /// Meta's iOS mock guide names), falling back to H.264 when the encoder refuses HEVC. Returns
    /// the codec used. Blocks; call off the main thread.
    static func writeMockFeed(to url: URL) throws -> String {
        do {
            try writeFeed(to: url, codec: .hevc)
            return "hevc"
        } catch {
            try writeFeed(to: url, codec: .h264)
            return "h264"
        }
    }

    private static func writeFeed(to url: URL, codec: AVVideoCodecType) throws {
        try? FileManager.default.removeItem(at: url)
        let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: codec, AVVideoWidthKey: mockWidth, AVVideoHeightKey: mockHeight,
        ])
        input.expectsMediaDataInRealTime = false
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: mockWidth, kCVPixelBufferHeightKey as String: mockHeight,
        ])
        guard writer.canAdd(input) else { throw feedError("the encoder refused \(codec.rawValue)") }
        writer.add(input)
        guard writer.startWriting() else { throw writer.error ?? feedError("the writer did not start") }
        writer.startSession(atSourceTime: .zero)
        for n in 0..<mockFrames {
            while !input.isReadyForMoreMediaData { Thread.sleep(forTimeInterval: 0.005) }
            guard let pool = adaptor.pixelBufferPool else { throw writer.error ?? feedError("no pixel buffer pool") }
            var pb: CVPixelBuffer?
            CVPixelBufferPoolCreatePixelBuffer(nil, pool, &pb)
            guard let buffer = pb else { throw feedError("no pixel buffer") }
            CVPixelBufferLockBaseAddress(buffer, [])
            let space = CGColorSpaceCreateDeviceRGB()
            if let ctx = CGContext(data: CVPixelBufferGetBaseAddress(buffer), width: mockWidth, height: mockHeight,
                                   bitsPerComponent: 8, bytesPerRow: CVPixelBufferGetBytesPerRow(buffer), space: space,
                                   bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue) {
                drawPattern(ctx, width: mockWidth, height: mockHeight, frame: n)
            }
            CVPixelBufferUnlockBaseAddress(buffer, [])
            guard adaptor.append(buffer, withPresentationTime: CMTime(value: CMTimeValue(n), timescale: mockFPS)) else {
                throw writer.error ?? feedError("a frame was refused")
            }
        }
        input.markAsFinished()
        let done = DispatchSemaphore(value: 0)
        writer.finishWriting { done.signal() }
        done.wait()
        guard writer.status == .completed else { throw writer.error ?? feedError("the feed was not finished") }
    }

    private static func feedError(_ message: String) -> NSError {
        NSError(domain: "GlassesMedia", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
    }
}

/// An H.264 MP4 clip of the stream's decoded frames. The size is taken from the first frame; DAT
/// may lower the resolution when Bluetooth bandwidth drops (Meta, "Camera Streaming"), and a
/// frame of another size ends the clip (`sizeChanged`). Confined to the frame queue.
final class GlassesClipWriter {
    let url: URL
    private let fps: Int
    private var writer: AVAssetWriter?
    private var input: AVAssetWriterInput?
    private var adaptor: AVAssetWriterInputPixelBufferAdaptor?
    /// The stream's audio (PCM from DAT at the stream's rate), encoded as AAC; nil for a silent clip.
    private let audioRate: Int?
    private let audioChannels: Int
    private var audioInput: AVAssetWriterInput?
    private(set) var audioSamples = 0
    private var width = 0
    private var height = 0
    private var first: CMTime?
    private var last: CMTime = .zero
    private(set) var frames = 0
    private(set) var sizeChanged = false
    private(set) var failed = false
    private var finished = false

    init(url: URL, fps: Int, audioRate: Int? = nil, audioChannels: Int = 1) {
        self.url = url
        self.fps = fps
        self.audioRate = audioRate
        self.audioChannels = audioChannels
    }

    /// Appends the stream's PCM (after the first video frame; earlier audio has no clip time).
    func appendAudio(_ pcm: AVAudioPCMBuffer, at pts: CMTime) {
        guard !finished, !failed, let audioInput, let first, pts >= first, audioInput.isReadyForMoreMediaData else { return }
        var asbd = pcm.format.streamDescription.pointee
        var format: CMAudioFormatDescription?
        guard CMAudioFormatDescriptionCreate(allocator: nil, asbd: &asbd, layoutSize: 0, layout: nil, magicCookieSize: 0,
                                             magicCookie: nil, extensions: nil, formatDescriptionOut: &format) == noErr,
              let format else { return }
        var timing = CMSampleTimingInfo(duration: CMTime(value: 1, timescale: CMTimeScale(asbd.mSampleRate)),
                                        presentationTimeStamp: pts, decodeTimeStamp: .invalid)
        var sample: CMSampleBuffer?
        guard CMSampleBufferCreate(allocator: nil, dataBuffer: nil, dataReady: false, makeDataReadyCallback: nil, refcon: nil,
                                   formatDescription: format, sampleCount: CMItemCount(pcm.frameLength), sampleTimingEntryCount: 1,
                                   sampleTimingArray: &timing, sampleSizeEntryCount: 0, sampleSizeArray: nil, sampleBufferOut: &sample) == noErr,
              let sample,
              CMSampleBufferSetDataBufferFromAudioBufferList(sample, blockBufferAllocator: nil, blockBufferMemoryAllocator: nil,
                                                             flags: 0, bufferList: pcm.audioBufferList) == noErr else { return }
        if audioInput.append(sample) { audioSamples += 1 }
    }

    /// Appends one decoded frame. False when it was not taken.
    @discardableResult
    func append(_ sample: CMSampleBuffer) -> Bool {
        guard !finished, !failed, let pixels = CMSampleBufferGetImageBuffer(sample) else { return false }
        let w = CVPixelBufferGetWidth(pixels)
        let h = CVPixelBufferGetHeight(pixels)
        if writer == nil && !open(width: w, height: h) { return false }
        guard w == width, h == height else {
            sizeChanged = true
            return false
        }
        guard let input, let adaptor, input.isReadyForMoreMediaData else { return false }
        let pts = CMSampleBufferGetPresentationTimeStamp(sample)
        if first == nil {
            first = pts
            writer?.startSession(atSourceTime: pts)
        }
        guard pts > last || frames == 0 else { return false }
        if adaptor.append(pixels, withPresentationTime: pts) {
            last = pts
            frames += 1
            return true
        }
        failed = true
        return false
    }

    private func open(width w: Int, height h: Int) -> Bool {
        width = w
        height = h
        guard let writer = try? AVAssetWriter(outputURL: url, fileType: .mp4) else {
            failed = true
            return false
        }
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: w, AVVideoHeightKey: h,
            AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: max(1_000_000, w * h * 4),
                                              AVVideoExpectedSourceFrameRateKey: fps,
                                              AVVideoMaxKeyFrameIntervalKey: fps],
        ])
        input.expectsMediaDataInRealTime = true
        guard writer.canAdd(input) else {
            failed = true
            return false
        }
        writer.add(input)
        if let audioRate {
            let a = AVAssetWriterInput(mediaType: .audio, outputSettings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: audioRate, AVNumberOfChannelsKey: audioChannels,
                AVEncoderBitRateKey: 64_000 * audioChannels,
            ])
            a.expectsMediaDataInRealTime = true
            if writer.canAdd(a) {
                writer.add(a)
                audioInput = a
            }
        }
        guard writer.startWriting() else {
            failed = true
            return false
        }
        self.writer = writer
        self.input = input
        adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: nil)
        return true
    }

    var durationMs: Int {
        guard let first, frames > 0 else { return 0 }
        return Int(((last - first).seconds * 1000).rounded()) + 1000 / max(1, fps)
    }

    /// Ends the clip. The handler gets true when the MP4 holds at least one frame.
    func finish(_ done: @escaping (Bool) -> Void) {
        guard !finished else { return done(false) }
        finished = true
        guard let writer, let input, frames > 0, !failed else {
            writer?.cancelWriting()
            return done(false)
        }
        input.markAsFinished()
        audioInput?.markAsFinished()
        writer.endSession(atSourceTime: last + CMTime(value: 1, timescale: CMTimeScale(max(1, fps))))
        writer.finishWriting { done(writer.status == .completed) }
    }
}
#endif
