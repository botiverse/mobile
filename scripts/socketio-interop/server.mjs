// Conformance server for compose/realtime-socket (RaftSocketIoInteropTest).
//
// Two real socket.io 4.8.3 servers:
//   PORT_A: non-default heartbeat (pingInterval 400 / pingTimeout 300) and maxHttpBufferSize 10000, so
//           the client must take them from the open packet.
//   PORT_B: connectionStateRecovery on, perMessageDeflate on (threshold 0).
// Both: namespace "/" with an auth middleware like Raft's (token "good"; "expired" and "sso" are rejected
// with the Raft messages), namespace "/admin" (auth.role must be "admin"), events, acks both ways,
// binary events and acks, server-side disconnect and transport drops.
//   PORT_C: a small Raft realtime server for the Android client parity suite
//           (SocketIoRealtimeClientTransportParityTest, both socket implementations): `rooms:joined` after
//           connect, `sync:horizon` ack, `sync:resume` -> `sync:resume:response`, `join:channel` ->
//           `channel:joined`, and an HTTP control API keyed by the handshake token (any token is accepted,
//           one per test): GET /raft/log, POST /raft/message?seq=N, /raft/drop, /raft/config?horizonReset=1.
//
// Usage: node scripts/socketio-interop/server.mjs   (prints "READY <portA> <portB>" when listening)
import { createServer } from "node:http";
import { Server } from "socket.io";

const portA = Number(process.env.PORT_A ?? 3901);
const portB = Number(process.env.PORT_B ?? 3902);
const portC = Number(process.env.PORT_C ?? 3903);

function wire(io, label) {
  io.use((socket, next) => {
    const token = socket.handshake.auth?.token;
    if (!token) return next(new Error("Authentication required"));
    if (token === "expired") return next(new Error("Invalid or expired token"));
    if (token === "sso") return next(new Error("SSO_REQUIRED"));
    if (token !== "good") return next(new Error("Invalid or expired token"));
    next();
  });

  io.on("connection", (socket) => {
    socket.join("all");
    socket.emit("welcome", { auth: socket.handshake.auth, id: socket.id, server: label, recovered: socket.recovered });

    socket.on("echo", (...args) => {
      const ack = typeof args[args.length - 1] === "function" ? args.pop() : null;
      if (ack) ack(...args);
      else socket.emit("echo", ...args);
    });

    socket.on("binary-echo", (payload, ack) => {
      const data = Buffer.isBuffer(payload?.data) ? payload.data : Buffer.alloc(0);
      ack({ received: data.length }, Buffer.from(data).reverse());
      socket.emit("binary", { name: "server.bin", data: Buffer.from([1, 2, 3, 250]) });
    });

    socket.on("ask-ack", () => {
      socket.timeout(5000).emit("needs-ack", { question: 42 }, (err, response) => {
        socket.emit("ack-result", { timedOut: Boolean(err), response: response ?? null });
      });
    });

    socket.on("disconnect-me", () => socket.disconnect());

    // Drops the transport (not a namespace disconnect) and emits "missed" while the client is away. With
    // connectionStateRecovery the server replays it before its CONNECT answer on the client's return.
    socket.on("drop-transport", () => {
      socket.once("disconnect", () => io.to("all").emit("missed", { n: 1 }));
      socket.conn.close();
    });
  });

  io.of("/admin").use((socket, next) => {
    if (socket.handshake.auth?.role !== "admin") return next(new Error("Not authorized"));
    next();
  });
  io.of("/admin").on("connection", (socket) => {
    socket.emit("admin-welcome", { id: socket.id });
    socket.on("echo", (value, ack) => ack({ admin: value }));
  });
}

function listen(port, options, label) {
  const httpServer = createServer();
  const io = new Server(httpServer, options);
  wire(io, label);
  return new Promise((resolve) => httpServer.listen(port, "127.0.0.1", resolve));
}


// PORT_C: Raft realtime behaviour, per handshake token (see the header).
function raftServer(port) {
  const sessions = new Map(); // token -> { log: [], sockets: Set, horizonReset: false }
  const session = (token) => {
    if (!sessions.has(token)) sessions.set(token, { log: [], sockets: new Set(), horizonReset: false });
    return sessions.get(token);
  };
  const httpServer = createServer((req, res) => {
    const url = new URL(req.url, "http://localhost");
    if (!url.pathname.startsWith("/raft/")) return;
    const token = url.searchParams.get("token") ?? "";
    const state = session(token);
    const reply = (body) => {
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify(body));
    };
    switch (url.pathname) {
      case "/raft/log":
        return reply(state.log);
      case "/raft/message": {
        const seq = Number(url.searchParams.get("seq"));
        const message = {
          id: `message-${seq}`, threadId: "channel-1", channelId: "channel-1", serverId: "server-1", seq,
          content: `Realtime body ${seq}`, messageType: "chat", senderType: "user", senderId: "user-1", senderName: "Artin",
        };
        state.sockets.forEach((socket) => socket.emit("message:new", message));
        return reply({ sockets: state.sockets.size });
      }
      case "/raft/drop": {
        const count = state.sockets.size;
        state.sockets.forEach((socket) => socket.conn.close());
        return reply({ dropped: count });
      }
      case "/raft/config":
        state.horizonReset = url.searchParams.get("horizonReset") === "1";
        return reply({ horizonReset: state.horizonReset });
      default:
        res.writeHead(404);
        res.end();
    }
  });
  const io = new Server(httpServer, { transports: ["websocket"] });
  io.use((socket, next) => (socket.handshake.auth?.token ? next() : next(new Error("Authentication required"))));
  io.on("connection", (socket) => {
    const auth = socket.handshake.auth;
    const state = session(auth.token);
    state.sockets.add(socket);
    state.log.push({ event: "connect", auth: { ...auth, token: undefined } });
    socket.onAny((event, ...args) => {
      const plain = args.filter((arg) => typeof arg !== "function");
      state.log.push({ event, args: plain });
    });
    socket.on("sync:horizon", (...args) => {
      const ack = args[args.length - 1];
      if (typeof ack === "function") ack({ horizon: "752" });
    });
    socket.on("sync:resume", (payload) => {
      socket.emit("sync:resume:response", {
        messages: [], currentSeq: payload?.lastSeq ?? 0, hasMore: false, horizon: "800", horizonReset: state.horizonReset,
      });
    });
    socket.on("join:channel", (channelId) => socket.emit("channel:joined", { channelId }));
    socket.on("disconnect", (reason) => {
      state.sockets.delete(socket);
      state.log.push({ event: "disconnect", reason });
    });
    // Like the real server, member rooms are joined after the handshake.
    setTimeout(() => socket.connected && socket.emit("rooms:joined", {}), 50);
  });
  return new Promise((resolve) => httpServer.listen(port, "127.0.0.1", resolve));
}

await listen(portA, { transports: ["websocket"], pingInterval: 400, pingTimeout: 300, maxHttpBufferSize: 10000 }, "a");
await listen(
  portB,
  {
    transports: ["websocket"],
    perMessageDeflate: { threshold: 0 },
    connectionStateRecovery: { maxDisconnectionDuration: 60000, skipMiddlewares: true },
  },
  "b",
);
await raftServer(portC);
console.log(`READY ${portA} ${portB} ${portC}`);
