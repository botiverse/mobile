// Conformance server for compose/realtime-socket (RaftSocketIoInteropTest).
//
// Two real socket.io 4.8.3 servers:
//   PORT_A: non-default heartbeat (pingInterval 400 / pingTimeout 300) and maxHttpBufferSize 10000, so
//           the client must take them from the open packet.
//   PORT_B: connectionStateRecovery on, perMessageDeflate on (threshold 0).
// Both: namespace "/" with an auth middleware like Raft's (token "good"; "expired" and "sso" are rejected
// with the Raft messages), namespace "/admin" (auth.role must be "admin"), events, acks both ways,
// binary events and acks, server-side disconnect and transport drops.
//
// Usage: node scripts/socketio-interop/server.mjs   (prints "READY <portA> <portB>" when listening)
import { createServer } from "node:http";
import { Server } from "socket.io";

const portA = Number(process.env.PORT_A ?? 3901);
const portB = Number(process.env.PORT_B ?? 3902);

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
console.log(`READY ${portA} ${portB}`);
