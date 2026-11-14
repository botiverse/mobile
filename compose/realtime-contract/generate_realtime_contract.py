#!/usr/bin/env python3
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCHEMA = Path(__file__).with_name("realtime_contract.json")
KOTLIN_OUT = ROOT / "core/src/commonMain/kotlin/build/raft/app/data/realtime/GeneratedRealtimeContract.kt"
SWIFT_OUT = ROOT / "iosApp/iosApp/GeneratedRealtimeContract.swift"
SOCKET_KOTLIN_OUT = (
    ROOT / "realtime-socket/src/commonMain/kotlin/build/raft/realtime/socket/GeneratedSocketHandshakeErrors.kt"
)


def load_contract():
    return json.loads(SCHEMA.read_text())


def kt_string(value):
    return json.dumps(value)


def kt_const(name, value, indent="        "):
    return f"{indent}const val {name} = {kt_string(value)}"


def swift_string(value):
    return json.dumps(value)


def write_if_changed(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() and path.read_text() == content:
        return
    path.write_text(content)


def require_exact_socket_names(contract, socket_names, language):
    """Fail when the hand-written socket name map and contract["socketEvents"] diverge.

    The generator iterates the map, not the JSON, so a socketEvents entry with no
    mapping would otherwise be dropped silently from the generated file.
    """
    contract_keys = set(contract["socketEvents"])
    mapped_keys = set(socket_names)
    missing = sorted(contract_keys - mapped_keys)
    extra = sorted(mapped_keys - contract_keys)
    if missing or extra:
        problems = []
        if missing:
            problems.append(f"socketEvents with no {language} name (add them to the {language} socket_names map): {', '.join(missing)}")
        if extra:
            problems.append(f"{language} socket_names entries not in socketEvents (remove them): {', '.join(extra)}")
        raise SystemExit(f"realtime_contract.json and {Path(__file__).name} disagree:\n  " + "\n  ".join(problems))


def require_server_wire_events(contract):
    """serverWireEvents must name socketEvents keys, each once."""
    server_wire = contract["serverWireEvents"]
    unknown = sorted(set(server_wire) - set(contract["socketEvents"]))
    duplicates = sorted({key for key in server_wire if server_wire.count(key) > 1})
    if unknown or duplicates:
        problems = []
        if unknown:
            problems.append(f"serverWireEvents not in socketEvents: {', '.join(unknown)}")
        if duplicates:
            problems.append(f"serverWireEvents listed more than once: {', '.join(duplicates)}")
        raise SystemExit("realtime_contract.json is inconsistent:\n  " + "\n  ".join(problems))


def generate_kotlin(contract):
    socket = contract["socketEvents"]
    server_wire = contract["serverWireEvents"]
    socket_names = {
        "connect": "Connect",
        "connectError": "ConnectError",
        "disconnect": "Disconnect",
        "reconnect": "Reconnect",
        "reconnectAttempt": "ReconnectAttempt",
        "reconnectError": "ReconnectError",
        "reconnectFailed": "ReconnectFailed",
        "roomsJoined": "RoomsJoined",
        "channelJoined": "ChannelJoined",
        "channelJoinDenied": "ChannelJoinDenied",
        "syncResume": "SyncResume",
        "syncHorizon": "SyncHorizon",
        "syncResumeResponse": "SyncResumeResponse",
        "joinChannel": "JoinChannel",
        "leaveChannel": "LeaveChannel",
        "heartbeat": "Heartbeat",
        "messageNew": "MessageNew",
        "messageUpdated": "MessageUpdated",
        "directMessageNew": "DirectMessageNew",
        "threadUpdated": "ThreadUpdated",
        "channelUpdated": "ChannelUpdated",
        "channelMembersUpdated": "ChannelMembersUpdated",
        "scopeReadUpdated": "ScopeReadUpdated",
        "taskCreated": "TaskCreated",
        "taskUpdated": "TaskUpdated",
        "taskDeleted": "TaskDeleted",
        "agentActivity": "AgentActivity",
        "agentActivityState": "AgentActivityState",
        "agentActivitySubscribe": "AgentActivitySubscribe",
        "agentActivityUnsubscribe": "AgentActivityUnsubscribe",
        "agentActivitySubscribed": "AgentActivitySubscribed",
        "agentSeen": "AgentSeen",
        "agentTyping": "AgentTyping",
        "agentSession": "AgentSession",
        "agentCreated": "AgentCreated",
        "agentUpdated": "AgentUpdated",
        "agentDeleted": "AgentDeleted",
        "machineStatus": "MachineStatus",
        "machineCapabilities": "MachineCapabilities",
        "machineUpdated": "MachineUpdated",
        "computerUpgradeProgress": "ComputerUpgradeProgress",
        "computerUpgradeDone": "ComputerUpgradeDone",
        "machineUpgradeRequest": "MachineUpgradeRequest",
        "computerRestartDone": "ComputerRestartDone",
        "machineRuntimeAuthUpdated": "MachineRuntimeAuthUpdated",
        "runtimeAuthLoginEvent": "RuntimeAuthLoginEvent",
        "runtimeAuthLoginResult": "RuntimeAuthLoginResult",
        "daemonStatus": "DaemonStatus",
        "serverMemberAdded": "ServerMemberAdded",
        "serverMemberUpdated": "ServerMemberUpdated",
        "serverMemberLeft": "ServerMemberLeft",
        "serverMemberRemoved": "ServerMemberRemoved",
        "serverMembershipRemoved": "ServerMembershipRemoved",
        "serverDeleted": "ServerDeleted",
        "serverPlanUpdated": "ServerPlanUpdated",
        "serverFeaturesChanged": "ServerFeaturesChanged",
        "appearanceUpdated": "AppearanceUpdated",
        "userProfileUpdated": "UserProfileUpdated",
        "productFeedbackUnread": "ProductFeedbackUnread",
        "productFeedbackAppUnread": "ProductFeedbackAppUnread",
        "activityChanged": "ActivityChanged",
        "announcementNew": "AnnouncementNew",
        "reminderFired": "ReminderFired",
        "reminderScheduled": "ReminderScheduled",
        "reminderCanceled": "ReminderCanceled",
        "threadFollowersUpdated": "ThreadFollowersUpdated",
        "threadReplyUpdated": "ThreadReplyUpdated",
        "readStateUpdated": "ReadStateUpdated",
        "readStateUpdatedBulk": "ReadStateUpdatedBulk",
        "notificationPrefsUpdated": "NotificationPrefsUpdated",
        "pinnedUpdated": "PinnedUpdated",
        "notificationPush": "NotificationPush",
        "notificationRetract": "NotificationRetract",
        "jointInvitesChanged": "JointInvitesChanged",
        "linkCardOperationChanged": "LinkCardOperationChanged",
        "actorUpdated": "ActorUpdated",
        "messageDisplayPrefsUpdated": "MessageDisplayPrefsUpdated",
    }
    require_exact_socket_names(contract, socket_names, "Kotlin")
    require_server_wire_events(contract)
    lines = [
        "package build.raft.app.data.realtime",
        "",
        "// Generated by compose/realtime-contract/generate_realtime_contract.py. Do not edit by hand.",
        "object GeneratedRealtimeContract {",
        "    object Notify {",
        kt_const("EventName", contract["notify"]["eventName"]),
        kt_const("RoomsEventName", contract["notify"]["roomsEventName"]),
        "    }",
        "",
        "    object RoomAction {",
        kt_const("JoinChannel", contract["roomActions"]["joinChannel"]),
        kt_const("LeaveChannel", contract["roomActions"]["leaveChannel"]),
        kt_const("RejoinChannel", contract["roomActions"]["rejoinChannel"]),
        kt_const("AgentActivitySubscribe", contract["roomActions"]["agentActivitySubscribe"]),
        kt_const("AgentActivityUnsubscribe", contract["roomActions"]["agentActivityUnsubscribe"]),
        "    }",
        "",
        "    object PayloadKey {",
        kt_const("Action", contract["payloadKeys"]["action"]),
        kt_const("ChannelId", contract["payloadKeys"]["channelId"]),
        kt_const("ServerId", contract["payloadKeys"]["serverId"]),
        kt_const("EventName", contract["payloadKeys"]["eventName"]),
        kt_const("RawPayload", contract["payloadKeys"]["rawPayload"]),
        "",
        "        val All: List<String> = listOf(",
        "            Action,",
        "            ChannelId,",
        "            ServerId,",
        "            EventName,",
        "            RawPayload,",
        "        )",
        "    }",
        "",
        "    object DisconnectReason {",
    ]
    for reason in contract["disconnectReasons"]["sessionEnd"]:
        const_name = "".join(part.capitalize() for part in reason.split("_"))
        lines.append(kt_const(const_name, reason))
    lines += [
        "        val SessionEnd: Set<String> = setOf(",
    ]
    for reason in contract["disconnectReasons"]["sessionEnd"]:
        const_name = "".join(part.capitalize() for part in reason.split("_"))
        lines.append(f"            {const_name},")
    lines += [
        "        )",
        "    }",
        "",
        "    object SocketEvent {",
    ]
    for key, name in socket_names.items():
        lines.append(kt_const(name, socket[key]))
    lines += [
        "",
        "        val ServerWireEvents: List<String> = listOf(",
    ]
    for key in server_wire:
        lines.append(f"            {socket_names[key]},")
    lines += [
        "        )",
        "    }",
        "}",
        "",
    ]
    return "\n".join(lines)


def generate_swift(contract):
    socket_names = {
        "connect": "connect",
        "connectError": "connectError",
        "disconnect": "disconnect",
        "reconnect": "reconnect",
        "reconnectAttempt": "reconnectAttempt",
        "reconnectError": "reconnectError",
        "reconnectFailed": "reconnectFailed",
        "roomsJoined": "roomsJoined",
        "channelJoined": "channelJoined",
        "channelJoinDenied": "channelJoinDenied",
        "syncResume": "syncResume",
        "syncHorizon": "syncHorizon",
        "syncResumeResponse": "syncResumeResponse",
        "joinChannel": "joinChannel",
        "leaveChannel": "leaveChannel",
        "heartbeat": "heartbeat",
        "messageNew": "messageNew",
        "messageUpdated": "messageUpdated",
        "directMessageNew": "directMessageNew",
        "threadUpdated": "threadUpdated",
        "channelUpdated": "channelUpdated",
        "channelMembersUpdated": "channelMembersUpdated",
        "scopeReadUpdated": "scopeReadUpdated",
        "taskCreated": "taskCreated",
        "taskUpdated": "taskUpdated",
        "taskDeleted": "taskDeleted",
        "agentActivity": "agentActivity",
        "agentActivityState": "agentActivityState",
        "agentActivitySubscribe": "agentActivitySubscribe",
        "agentActivityUnsubscribe": "agentActivityUnsubscribe",
        "agentActivitySubscribed": "agentActivitySubscribed",
        "agentSeen": "agentSeen",
        "agentTyping": "agentTyping",
        "agentSession": "agentSession",
        "agentCreated": "agentCreated",
        "agentUpdated": "agentUpdated",
        "agentDeleted": "agentDeleted",
        "machineStatus": "machineStatus",
        "machineCapabilities": "machineCapabilities",
        "machineUpdated": "machineUpdated",
        "computerUpgradeProgress": "computerUpgradeProgress",
        "computerUpgradeDone": "computerUpgradeDone",
        "machineUpgradeRequest": "machineUpgradeRequest",
        "computerRestartDone": "computerRestartDone",
        "machineRuntimeAuthUpdated": "machineRuntimeAuthUpdated",
        "runtimeAuthLoginEvent": "runtimeAuthLoginEvent",
        "runtimeAuthLoginResult": "runtimeAuthLoginResult",
        "daemonStatus": "daemonStatus",
        "serverMemberAdded": "serverMemberAdded",
        "serverMemberUpdated": "serverMemberUpdated",
        "serverMemberLeft": "serverMemberLeft",
        "serverMemberRemoved": "serverMemberRemoved",
        "serverMembershipRemoved": "serverMembershipRemoved",
        "serverDeleted": "serverDeleted",
        "serverPlanUpdated": "serverPlanUpdated",
        "serverFeaturesChanged": "serverFeaturesChanged",
        "appearanceUpdated": "appearanceUpdated",
        "userProfileUpdated": "userProfileUpdated",
        "productFeedbackUnread": "productFeedbackUnread",
        "productFeedbackAppUnread": "productFeedbackAppUnread",
        "activityChanged": "activityChanged",
        "announcementNew": "announcementNew",
        "reminderFired": "reminderFired",
        "reminderScheduled": "reminderScheduled",
        "reminderCanceled": "reminderCanceled",
        "threadFollowersUpdated": "threadFollowersUpdated",
        "threadReplyUpdated": "threadReplyUpdated",
        "readStateUpdated": "readStateUpdated",
        "readStateUpdatedBulk": "readStateUpdatedBulk",
        "notificationPrefsUpdated": "notificationPrefsUpdated",
        "pinnedUpdated": "pinnedUpdated",
        "notificationPush": "notificationPush",
        "notificationRetract": "notificationRetract",
        "jointInvitesChanged": "jointInvitesChanged",
        "linkCardOperationChanged": "linkCardOperationChanged",
        "actorUpdated": "actorUpdated",
        "messageDisplayPrefsUpdated": "messageDisplayPrefsUpdated",
    }
    require_exact_socket_names(contract, socket_names, "Swift")
    require_server_wire_events(contract)
    lines = [
        "// Generated by compose/realtime-contract/generate_realtime_contract.py. Do not edit by hand.",
        "enum GeneratedRealtimeContract {",
        "    enum Notify {",
        f"        static let eventName = {swift_string(contract['notify']['eventName'])}",
        f"        static let roomsEventName = {swift_string(contract['notify']['roomsEventName'])}",
        "    }",
        "",
        "    enum RoomAction {",
        f"        static let joinChannel = {swift_string(contract['roomActions']['joinChannel'])}",
        f"        static let leaveChannel = {swift_string(contract['roomActions']['leaveChannel'])}",
        f"        static let rejoinChannel = {swift_string(contract['roomActions']['rejoinChannel'])}",
        f"        static let agentActivitySubscribe = {swift_string(contract['roomActions']['agentActivitySubscribe'])}",
        f"        static let agentActivityUnsubscribe = {swift_string(contract['roomActions']['agentActivityUnsubscribe'])}",
        "    }",
        "",
        "    enum PayloadKey {",
    ]
    for key in ["action", "channelId", "serverId", "eventName", "rawPayload"]:
        lines.append(f"        static let {key} = {swift_string(contract['payloadKeys'][key])}")
    lines.append("        static let all: [String] = [PayloadKey.action, PayloadKey.channelId, PayloadKey.serverId, PayloadKey.eventName, PayloadKey.rawPayload]")
    lines += [
        "    }",
        "",
        "    enum DisconnectReason {",
    ]
    for reason in contract["disconnectReasons"]["sessionEnd"]:
        name = reason.split("_")[0] + "".join(part.capitalize() for part in reason.split("_")[1:])
        lines.append(f"        static let {name} = {swift_string(reason)}")
    reasons = ", ".join("DisconnectReason." + reason.split("_")[0] + "".join(part.capitalize() for part in reason.split("_")[1:]) for reason in contract["disconnectReasons"]["sessionEnd"])
    lines += [
        f"        static let sessionEnd: Set<String> = [{reasons}]",
        "    }",
        "",
        "    enum SocketEvent {",
    ]
    for key, name in socket_names.items():
        lines.append(f"        static let {name} = {swift_string(contract['socketEvents'][key])}")
    # The same list as Kotlin `GeneratedRealtimeContract.SocketEvent.ServerWireEvents`: the iOS host
    # subscribes from it, so a new server event reaches both platforms (no hand-kept Swift list).
    lines += [
        "",
        "        static let serverWireEvents: [String] = [",
    ]
    for key in contract["serverWireEvents"]:
        lines.append(f"            SocketEvent.{socket_names[key]},")
    lines += [
        "        ]",
        "    }",
        "}",
        "",
    ]
    return "\n".join(lines)


def generate_socket_handshake_errors(contract):
    """The handshake (`connect_error`) messages for :realtime-socket, from `handshakeErrors`.

    `handshakeErrors` is written by sync_from_slock.py from botiverse/slock; the client types
    every message (RaftSocketHandshakeError), and RaftSocketHandshakeErrorTest fails on one it
    does not know.
    """
    errors = contract.get("handshakeErrors")
    if not errors:
        raise SystemExit("realtime_contract.json has no handshakeErrors; run sync_from_slock.py --write")
    names = []
    lines = [
        "package build.raft.realtime.socket",
        "",
        "// Generated by compose/realtime-contract/generate_realtime_contract.py from realtime_contract.json",
        "// `handshakeErrors` (synced from botiverse/slock by sync_from_slock.py). Do not edit by hand.",
        "object GeneratedSocketHandshakeErrors {",
    ]
    for key, message in errors.items():
        name = key[:1].upper() + key[1:]
        names.append(name)
        lines.append(kt_const(name, message, indent="    "))
    lines += [
        "",
        "    val All: List<String> = listOf(",
    ]
    for name in names:
        lines.append(f"        {name},")
    lines += [
        "    )",
        "}",
        "",
    ]
    return "\n".join(lines)


def main():
    contract = load_contract()
    write_if_changed(KOTLIN_OUT, generate_kotlin(contract))
    write_if_changed(SWIFT_OUT, generate_swift(contract))
    write_if_changed(SOCKET_KOTLIN_OUT, generate_socket_handshake_errors(contract))


if __name__ == "__main__":
    main()
