import Foundation

// Runs display-vectors.json through GlassesDisplayTree; DisplayTreeVectors.java prints the same lines.
func canon(_ n: GlassesDisplayTree.Node) -> String {
    var s = "(" + n.kind.rawValue
    for (k, v) in n.attrs.values.sorted(by: { $0.key.utf16.lexicographicallyPrecedes($1.key.utf16) }) { s += " \(k)=\(v)" }
    for c in n.children { s += " " + canon(c) }
    return s + ")"
}

let data = try! Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))
let all = try! JSONSerialization.jsonObject(with: data) as! [[String: Any]]
for v in all {
    let line: String
    if let n = try? GlassesDisplayTree.parse(v["doc"] as! String) { line = "ok " + canon(n) } else { line = "bad" }
    print("\(v["name"] as! String)\t\(line)")
}
