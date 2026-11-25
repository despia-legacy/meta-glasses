// Drives the iOS GlassesClipWriter (copied out of ios/app/GlassesMedia.swift by test_glasses_clip.py) the way the
// stream does: decoded BGRA frames as CMSampleBuffers, then finish. Prints "CLIP <frames> <bytes> <completed>".
import AVFoundation
import CoreMedia
import CoreVideo
import Foundation

let out = URL(fileURLWithPath: CommandLine.arguments[1])
let count = 12
let fps = 24
let writer = GlassesClipWriter(url: out, fps: fps)

func frame(_ n: Int) -> CMSampleBuffer {
    var pb: CVPixelBuffer?
    CVPixelBufferCreate(nil, 64, 96, kCVPixelFormatType_32BGRA,
                        [kCVPixelBufferIOSurfacePropertiesKey as String: [:]] as CFDictionary, &pb)
    let pixels = pb!
    CVPixelBufferLockBaseAddress(pixels, [])
    memset(CVPixelBufferGetBaseAddress(pixels), Int32(n * 16 % 255), CVPixelBufferGetDataSize(pixels))
    CVPixelBufferUnlockBaseAddress(pixels, [])
    var format: CMVideoFormatDescription?
    CMVideoFormatDescriptionCreateForImageBuffer(allocator: nil, imageBuffer: pixels, formatDescriptionOut: &format)
    var timing = CMSampleTimingInfo(duration: CMTime(value: 1, timescale: CMTimeScale(fps)),
                                    presentationTimeStamp: CMTime(value: CMTimeValue(100 + n), timescale: CMTimeScale(fps)),
                                    decodeTimeStamp: .invalid)
    var sample: CMSampleBuffer?
    CMSampleBufferCreateReadyWithImageBuffer(allocator: nil, imageBuffer: pixels, formatDescription: format!,
                                             sampleTiming: &timing, sampleBufferOut: &sample)
    return sample!
}

var taken = 0
for n in 0..<count {
    // Real time input: wait briefly when the encoder is busy, as frames keep coming on a live stream.
    var tries = 0
    while !writer.append(frame(n)) && tries < 200 && !writer.failed {
        Thread.sleep(forTimeInterval: 0.005)
        tries += 1
    }
    taken = writer.frames
}
let done = DispatchSemaphore(value: 0)
var completed = false
writer.finish { ok in
    completed = ok
    done.signal()
}
_ = done.wait(timeout: .now() + 20)
let bytes = ((try? FileManager.default.attributesOfItem(atPath: out.path))?[.size] as? Int) ?? 0
print("CLIP \(taken) \(bytes) \(completed)")
