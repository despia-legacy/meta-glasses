import Foundation
import Network

let server = try GlassesServer(mediaDirectory: URL(fileURLWithPath: CommandLine.arguments[1]))
server.publish(Data([0xff, 0xd8, 0xff, 0xd9]))
print("READY \(server.port) \(server.token)")
fflush(stdout)
while let command = readLine() {
    if command == "quit" { break }
    if command == "frame" { server.publish(Data([0xff, 0xd8, 0xff, 0xd9])) }
}
server.close()
