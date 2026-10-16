import Foundation

@main
struct ExternalLinkIngressTests {
    static func main() {
        var offers: [RaftIosExternalLinkOffer] = []
        var disconnects: [String] = []
        var nextId = 0
        let ingress = RaftIosExternalLinkIngress(
            makeActivationId: { nextId += 1; return "activation-\(nextId)" },
            offer: { offers.append($0) },
            disconnect: { disconnects.append($0) }
        )
        let a = RaftIosExternalLinkOrigin(sceneId: "scene-a", shellScopeId: "shell-a")
        let b = RaftIosExternalLinkOrigin(sceneId: "scene-b", shellScopeId: "shell-b")
        func activity(_ raw: String, type: String = NSUserActivityTypeBrowsingWeb) -> NSUserActivity {
            let value = NSUserActivity(activityType: type)
            value.webpageURL = URL(string: raw)
            return value
        }
        let raw = "https://app.raft.build/s/team/channel/c?msg=a%2Fb&msg=duplicate#fragment"
        let cold = activity(raw)
        precondition(ingress.receive(cold, origin: a))
        precondition(offers.count == 1, "Cold delivery must reach shared before a page signals readiness")
        precondition(offers[0].rawURL == raw, "Shared must see original query/escaping, including invalid fields")
        precondition(offers[0].origin == a, "Actual scene and shell must survive delivery")

        precondition(ingress.receive(cold, origin: a))
        precondition(offers[0].activationId == offers[1].activationId, "Repeated OS delivery must be deduplicable")
        precondition(ingress.receive(cold, origin: b))
        precondition(offers[2].activationId != offers[0].activationId, "A different window owns a different activation")
        precondition(offers[2].origin == b)
        precondition(ingress.receive(activity(raw), origin: a))
        precondition(offers[3].activationId != offers[0].activationId, "A new tap of the same link must remain possible")

        let countBeforeReject = offers.count
        for invalid in [activity("http://app.raft.build/s/t/channel/c"),
                        NSUserActivity(activityType: "build.raft.app.oauth"),
                        activity(raw, type: "build.raft.app.shell.window"),
                        NSUserActivity(activityType: NSUserActivityTypeBrowsingWeb)] {
            precondition(!ingress.receive(invalid, origin: a))
        }
        precondition(!ingress.receive(cold, origin: .init(sceneId: "", shellScopeId: "shell")))
        precondition(!ingress.receive(cold, origin: .init(sceneId: "scene", shellScopeId: "")))
        precondition(offers.count == countBeforeReject, "Non-web activities, OAuth and missing owners must not reach external-link ingress")

        ingress.sceneDisconnected(a.sceneId)
        precondition(disconnects == ["scene-a"], "Disconnect must target the actual scene")
        precondition(ingress.receive(cold, origin: b))
        precondition(offers.last!.activationId == offers[2].activationId, "Disconnecting A must preserve B")
        precondition(ingress.receive(cold, origin: a))
        precondition(offers.last!.activationId != offers[0].activationId, "Reconnected scene must not reuse retired delivery identity")
        let beforeRetarget = offers.last!.activationId
        let retargeted = RaftIosExternalLinkOrigin(sceneId: a.sceneId, shellScopeId: "replacement-shell")
        precondition(ingress.receive(cold, origin: retargeted))
        precondition(offers.last!.activationId != beforeRetarget, "Retargeting a scene must not inherit another shell's activation")
        let beforeURLChange = offers.last!.activationId
        cold.webpageURL = URL(string: "https://app.raft.build/s/team/dm/other")
        precondition(ingress.receive(cold, origin: retargeted))
        precondition(offers.last!.activationId != beforeURLChange, "Changing an activity's URL is a new intent")
        print("PASS: external-link ingress ownership, cold delivery, repeat/new activation and disconnect")
    }
}
