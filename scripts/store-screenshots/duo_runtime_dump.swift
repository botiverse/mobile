// Lists the Objective-C classes and selectors in the simulator frameworks that look like they
// control a foldable device (fold state, hinge, posture, displays). Probe for the Duo store shots.
// Usage: xcrun swift duo_runtime_dump.swift <framework binary>...
import Foundation
import ObjectiveC

let pattern = try! NSRegularExpression(pattern: "fold|hinge|posture|angle|lid|clamshell|book|duo|displayconfig|screenconfig|openstate|closedstate|devicestate", options: [.caseInsensitive])
func hit(_ s: String) -> Bool { pattern.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) != nil }

for path in CommandLine.arguments.dropFirst() {
    guard dlopen(path, RTLD_NOW) != nil else { print("dlopen failed: \(path) \(String(cString: dlerror()))"); continue }
    var count: UInt32 = 0
    guard let names = objc_copyClassNamesForImage(path, &count) else { print("no classes: \(path)"); continue }
    print("### \(path): \(count) classes")
    for i in 0..<Int(count) {
        let name = String(cString: names[i])
        guard let cls = objc_getClass(names[i]) as? AnyClass else { continue }
        var lines: [String] = []
        for (meta, prefix) in [(false, "-"), (true, "+")] {
            guard let target: AnyClass = meta ? object_getClass(cls) : cls else { continue }
            var mc: UInt32 = 0
            if let ms = class_copyMethodList(target, &mc) {
                for j in 0..<Int(mc) {
                    let sel = NSStringFromSelector(method_getName(ms[j]))
                    if hit(sel) || hit(name) { lines.append("  \(prefix)\(sel)") }
                }
                free(ms)
            }
        }
        var pc: UInt32 = 0
        if let ps = class_copyPropertyList(cls, &pc) {
            for j in 0..<Int(pc) {
                let p = String(cString: property_getName(ps[j]))
                if hit(p) { lines.append("  @property \(p) \(String(cString: property_getAttributes(ps[j])!))") }
            }
            free(ps)
        }
        if !lines.isEmpty || hit(name) {
            print("\(name)")
            lines.forEach { print($0) }
        }
    }
}
