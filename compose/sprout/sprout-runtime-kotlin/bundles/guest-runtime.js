
(function (G) {
  "use strict";
  // ---- primordials -------------------------------------------------------------------
  // This script runs before any program, in the realm the program will run in. A program
  // can replace every built-in afterwards (Function.prototype.apply / call, Map.prototype
  // methods, JSON, Object.defineProperty ...). So what the runtime's own logic calls is
  // taken here, once, and called through Reflect.apply (itself taken here): no internal
  // function is ever the receiver or an argument of a method that is looked up later.
  var rApply = Reflect.apply;
  var defineProp = Object.defineProperty, freeze = Object.freeze, objectIs = Object.is;
  var hasOwnFn = Object.prototype.hasOwnProperty;
  function hasOwn(o, k) { return rApply(hasOwnFn, o, [k]); }
  var jsonParse = JSON.parse, jsonStringify = JSON.stringify;
  var isArray = Array.isArray, StringC = String, NumberC = Number, ErrorC = Error, FunctionC = Function, ProxyC = Proxy, MapC = Map;
  var mGet = MapC.prototype.get, mSet = MapC.prototype.set, mDelete = MapC.prototype.delete, mForEach = MapC.prototype.forEach;
  var mSize = Object.getOwnPropertyDescriptor(MapC.prototype, "size").get;
  function mapGet(m, k) { return rApply(mGet, m, [k]); }
  function mapSet(m, k, v) { rApply(mSet, m, [k, v]); }
  function mapDelete(m, k) { rApply(mDelete, m, [k]); }
  function mapForEach(m, fn) { rApply(mForEach, m, [fn]); }
  function mapSize(m) { return rApply(mSize, m, []); }
  function mapFirstKey(m) {
    var first, found = false;
    mapForEach(m, function (v, k) { if (!found) { found = true; first = k; } });
    return first;
  }
  var sSlice = StringC.prototype.slice, sCharCodeAt = StringC.prototype.charCodeAt;
  function strSlice(s, a, b) { return rApply(sSlice, s, [a, b]); }
  function charCode(s, at) { return rApply(sCharCodeAt, s, [at]); }

  var FRAGMENT = { $frag: true };
  // Host functions are taken out of the global object: agent code cannot call them.
  var hostLog = G.__log;
  delete G.__log;
  var hostNow = G.__now;
  delete G.__now;
  var hostEnter = G.__enter;
  delete G.__enter;
  var hostDenied = G.__denied;
  delete G.__denied;
  // Without these three there is no log, no clock and NO GUARD on the entry points: the
  // script refuses to start instead of running unguarded.
  if (typeof hostLog !== "function" || typeof hostNow !== "function" || typeof hostEnter !== "function") {
    throw new ErrorC("guest runtime: the host bindings __log, __now and __enter are required");
  }

  // ---- reports that repeat: said once, counted after (see the head of this file) ------
  var REPORT_ENTRIES = 32, REPORT_TEXT = 300, REPORT_BYTES = 8192;
  var reports = new MapC(); // key -> { l: level, m: text, n: count }
  var reportBytes = 0, reportSuppressed = 0;
  function utf8Bytes(s) {
    var bytes = 0;
    for (var at = 0; at < s.length; at++) {
      var c = charCode(s, at);
      if (c < 0x80) bytes += 1;
      else if (c < 0x800) bytes += 2;
      else if (c >= 0xd800 && c <= 0xdbff && at + 1 < s.length && charCode(s, at + 1) >= 0xdc00 && charCode(s, at + 1) <= 0xdfff) {
        // A high surrogate directly followed by a low one: one character, four bytes.
        bytes += 4;
        at++;
      } else {
        // The rest of the BMP, and a surrogate without its other half: an encoder writes
        // U+FFFD for that, which is three bytes too.
        bytes += 3;
      }
    }
    return bytes;
  }
  // key: what makes two reports the same one; without it, the level and the stored text.
  function reportOnce(level, message, key) {
    var text = StringC(message);
    if (text.length > REPORT_TEXT) {
      // The cut never makes half a character: when it falls inside a pair (a high
      // surrogate at the last place, its low one just behind the cut) the pair goes as a
      // whole. A surrogate that was alone already stays (it is three bytes either way).
      var last = charCode(text, REPORT_TEXT - 1), behind = charCode(text, REPORT_TEXT);
      text = strSlice(text, 0, last >= 0xd800 && last <= 0xdbff && behind >= 0xdc00 && behind <= 0xdfff ? REPORT_TEXT - 1 : REPORT_TEXT);
    }
    var id = key === undefined ? level + ":" + text : key;
    var known = mapGet(reports, id);
    if (known) { known.n++; return; }
    var bytes = utf8Bytes(text);
    if (mapSize(reports) >= REPORT_ENTRIES || reportBytes + bytes > REPORT_BYTES) { reportSuppressed++; return; }
    mapSet(reports, id, { l: level, m: text, n: 1 });
    reportBytes += bytes;
    hostLog(level, text);
  }

  // ---- origins: what agent code is running, for what it may ask of the host ----------
  // "module" (a program or statement body), "render" (a component body), "effect" (a
  // useEffect body or cleanup), "timer" (a setTimeout / setInterval / frame callback),
  // "event" (a handler the host dispatched), "none" (no agent code). ONLY "event" may
  // write shared state or ask for an action (reply, openLink): everything else may change
  // local state only. A request from another origin is dropped here, before anything
  // changes, and the host is told (__denied) so that it can report it. A callback started
  // by a handler (a timer, a promise) does not inherit the handler's origin.
  var origin = "none";
  function denied(what, detail) {
    if (typeof hostDenied === "function") hostDenied(what, origin, strSlice(StringC(detail), 0, 200));
    else hostLog("warn", what + " from " + origin + " was dropped: only an event handler may do this");
  }
  // Every entry point below is the host's to call. The host function __enter(name)
  // answers 1 once per call the host makes, for the entry point the host is calling;
  // agent code that calls an entry point itself (it runs inside such a call) gets 0 and
  // an error. So agent code cannot dispatch to itself to look like an event, or load /
  // tick / set the epoch.
  // The inner function is called DIRECTLY, with its (at most six) parameters by name:
  // not through fn.apply / fn.call, which a program can replace and would then be handed
  // the unguarded inner function as its receiver.
  // An entry starts and ends with origin "none" whatever the last call left: a call the
  // host interrupted (its time budget) unwinds without running a finally block, so the
  // origin of an interrupted event handler must not be the next call's.
  function entry(name, fn) {
    var guarded = function (a, b, c, d, e, f) {
      if (hostEnter(name) !== 1) throw new ErrorC(name + " is the host's to call");
      origin = "none";
      try { return fn(a, b, c, d, e, f); } finally { origin = "none"; }
    };
    defineProp(G, name, { value: guarded, writable: false, configurable: false, enumerable: false });
  }

  var instances = new MapC(); // path -> { name, hooks: [], gen }
  var handlers = [];         // fn id -> function, of the last render that succeeded
  var building = [];         // handler table of the render in progress
  var jsxNames = {};         // capitalized names the loaded code uses as JSX tags
  var root = null;           // root component of the code loaded last
  var goodRoot = null;       // root of the last render that succeeded
  var cur = null;            // instance being rendered
  var hookIdx = 0;
  var dirty = false;         // a state setter changed something
  var gen = 0;               // render generation, for sweeping unmounted instances
  var pendingEffects = [];
  var nodeCount = 0;
  var codeVersion = 0;       // bumped by every __load: internal (which hooks re-read their initial value)
  var runtimeEpoch = 1;      // the frame's runtimeEpoch and every binding's e: given by the host (__epoch), never counted here
  var frameSeq = 0;          // frames that came out; the frame's frameRevision
  var PROTOCOL = "genui.tree/1";
  var PRIMITIVES = { div: 1, span: 1, svg: 1, path: 1, circle: 1, rect: 1, g: 1, text: 1, linearGradient: 1, stop: 1 }; // @genui/protocol PRIMITIVE_TAGS
  var notes = [];            // diagnostics of the render pass in progress
  var MAX_NOTES = 8;
  function note(code, message, nodeId) {
    if (notes.length >= MAX_NOTES) return;
    var d = { code: code, phase: "render", message: message.slice(0, 300) };
    if (nodeId !== undefined) d.nodeId = nodeId;
    notes.push(d);
  }
  var MAX_NODES = 20000;     // hard stop; the host enforces the real (lower) limit
  var MAX_DEPTH = 120;

  // ---- cells and references (see the notes above) --------------------------------
  var cellEpoch = 1, refEpoch = 1;
  var delta = false;         // the host keeps tree parts by id
  var refSeq = 0;            // last id given to a tree part
  var refDefs = 0;           // ids given in this refEpoch
  var stable = new MapC();    // negative handler id -> { f, e: refEpoch }
  var stableSeq = 0;
  var stableSwept = 0;       // refEpoch of the last tree that came out
  var refOutEpoch = 0, refOutFrame = 0; // the reference frame that came out last: its refEpoch and frameRevision
  var owner = 0;             // > 0 while a part that gets an id is being walked
  var instLog = [];          // component instances rendered in this pass, in order
  var madeParts = [];        // elements that got ids in this pass (taken back if it fails)
  var lastNodes = 0;
  var cellHits = 0, cellMisses = 0, refsSent = 0;
  function newRefEpoch() { refEpoch++; refDefs = 0; }
  // Agent code ran outside a render: values may have changed in place.
  function agentRan() { cellEpoch++; newRefEpoch(); }
  // Two values a cell read are the same: the same value; two functions of the same
  // place in the source that close over the same values (__f); or, for what a cell reads
  // directly, two arrays or plain objects with the same members (a component body makes
  // "rows.filter(...)" and "{ gap: 4 }" new on every run). A cell that is kept then
  // holds the earlier array or function: equal in content, not in identity.
  var getProto = Object.getPrototypeOf, arrayProto = Array.prototype, objectProto = Object.prototype, ownKeys = Object.keys;
  var MAX_COMPARED = 64;
  function sameDep(a, b, depth) {
    if (objectIs(a, b)) return true;
    var t = typeof a, i;
    if (t !== typeof b || a === null || b === null || depth > 2) return false;
    if (t === "function") {
      if (a.$i === undefined || a.$i !== b.$i) return false;
      var x = a.$d, y = b.$d;
      if (x.length !== y.length) return false;
      for (i = 0; i < x.length; i++) if (!sameDep(x[i], y[i], depth + 1)) return false;
      return true;
    }
    if (t !== "object") return false;
    var proto = getProto(a);
    if (proto !== getProto(b)) return false;
    if (proto === arrayProto) {
      if (a.length !== b.length || a.length > MAX_COMPARED) return false;
      for (i = 0; i < a.length; i++) if (!objectIs(a[i], b[i]) && !(typeof a[i] === "function" && sameDep(a[i], b[i], depth + 1))) return false;
      return true;
    }
    if (proto !== objectProto || a.$el === true) return false;
    var keys = ownKeys(a);
    if (keys.length > MAX_COMPARED || keys.length !== ownKeys(b).length) return false;
    for (i = 0; i < keys.length; i++) {
      var k = keys[i], u = a[k], v = b[k];
      if (!objectIs(u, v) && !(typeof u === "function" && sameDep(u, v, depth + 1))) return false;
      if (v === undefined && !(k in b)) return false;
    }
    return true;
  }
  var cellFns = {};          // cell id -> function (values it reads) { return h(...) }, compiled once
  function cellCode(id) {
    var fn = cellFns[id];
    if (fn === undefined) throw new ErrorC("Unknown cell " + id);
    return fn;
  }
  function newCell() { return { g: gen, n: 0, d: null, v: undefined, e: 0 }; }
  function cell(id, deps, thunk) {
    var inst = cur;
    if (inst === null) return thunk ? thunk() : rApply(cellCode(id), undefined, deps);
    var cells = inst.cells;
    if (cells === undefined) cells = inst.cells = {};
    var c = cells[id];
    if (c === undefined) c = cells[id] = newCell();
    else if (c.g === gen) {
      // Evaluated again in the same render of this instance (a loop, a callback).
      var k = id + "#" + (++c.n), o = cells[k];
      if (o === undefined) o = cells[k] = newCell();
      c = o;
    } else { c.g = gen; c.n = 0; }
    if (c.e === cellEpoch) {
      var d = c.d, n = deps.length, i = 0;
      if (d.length === n) {
        for (; i < n; i++) if (!sameDep(d[i], deps[i], 0)) break;
        if (i === n) { cellHits++; return c.v; }
      }
    }
    var v = thunk ? thunk() : rApply(cellCode(id), undefined, deps);
    c.d = deps; c.v = v; c.e = cellEpoch;
    if (v !== null && typeof v === "object" && v.$el === true) v.$c = true;
    cellMisses++;
    return v;
  }
  function tagFunction(id, deps, fn, name) {
    if (typeof fn !== "function") return fn;
    fn.$i = id; fn.$d = deps;
    if (name && fn.name === "") { try { defineProp(fn, "name", { value: name, configurable: true }); } catch (e) {} }
    return fn;
  }

  function h(type, props) {
    var children = [];
    for (var i = 2; i < arguments.length; i++) children.push(arguments[i]);
    var p = props || {};
    if (children.length === 0 && p.children !== undefined) children = [p.children];
    return { $el: true, type: type, props: p, key: p.key != null ? StringC(p.key) : null, children: children };
  }

  // ---- hooks -------------------------------------------------------------
  function slot(kind, make) {
    if (!cur) throw new ErrorC("Hooks can only be called inside a component");
    var i = hookIdx++;
    var s = cur.hooks[i];
    // A different kind at the same index means the code changed shape (streaming): start over.
    if (!s || s.kind !== kind) { s = make(); s.kind = kind; cur.hooks[i] = s; }
    return s;
  }
  function depsChanged(a, b) {
    if (!a || !b || a.length !== b.length) return true;
    for (var i = 0; i < a.length; i++) if (!objectIs(a[i], b[i])) return true;
    return false;
  }
  // Cheap fingerprint of an initial value, to notice that newly loaded code spells it differently.
  function signature(v) {
    if (typeof v === "function") return "fn:" + v.toString();
    try { return jsonStringify(v); } catch (e) { return "?"; }
  }
  function useReducer(reducer, initArg, init) {
    var s = slot("state", function () {
      var st = { v: init ? init(initArg) : initArg, reducer: reducer, sig: signature(initArg), ver: codeVersion, touched: false };
      st.set = function (action) {
        var next = st.reducer(st.v, action);
        st.touched = true;
        if (!objectIs(next, st.v)) { st.v = next; dirty = true; }
      };
      return st;
    });
    if (s.ver !== codeVersion) {
      s.ver = codeVersion;
      var sig = signature(initArg);
      if (sig !== s.sig) { s.sig = sig; if (!s.touched) s.v = init ? init(initArg) : initArg; }
    }
    s.reducer = reducer;
    return [s.v, s.set];
  }
  function stateReducer(prev, action) { return typeof action === "function" ? action(prev) : action; }
  function useState(init) {
    return useReducer(stateReducer, init, function (v) { return typeof v === "function" ? v() : v; });
  }
  // ---- shared state (useSharedState) ----------------------------------------
  var hostShared = G.__sharedWrite;
  delete G.__sharedWrite;
  var SHARED_MAX = 65536;
  var shared = {};           // key -> value: the block's saved object
  function hasShared(key) { return hasOwn(shared, key); }
  // key -> the initial value the loaded code gives it, for the hooks the renders since the
  // last load read. The host reads it (__sharedDefaults) to create a saved state that
  // does not exist yet; nothing in here changes what a hook returns.
  var sharedDefaults = {}, sharedDefaultsVer = -1;
  entry("__sharedDefaults", function () {
    var out = {};
    for (var k in sharedDefaults) {
      var json;
      try { json = jsonStringify(sharedDefaults[k] === undefined ? null : sharedDefaults[k]); } catch (e) { json = undefined; }
      if (typeof json === "string") out[k] = jsonParse(json);
    }
    return jsonStringify(out);
  });
  function useSharedState(key, init) {
    if (typeof key !== "string" || key === "") throw new ErrorC("useSharedState(key, initial): key must be a non-empty string");
    var s = slot("shared", function () {
      var st = { key: key, initV: undefined, ver: -1 };
      st.set = function (action) {
        var k = st.key;
        // Only an event handler writes shared state (see "origins"): nothing changes otherwise.
        if (origin !== "event") { denied("state", k); return; }
        var prev = hasShared(k) ? shared[k] : st.initV;
        var next = typeof action === "function" ? action(prev) : action;
        var json = jsonStringify(next === undefined ? null : next);
        if (typeof json !== "string") throw new ErrorC("useSharedState(\"" + k + "\"): the value must be JSON (no functions)");
        var had = hasShared(k), old = shared[k];
        shared[k] = jsonParse(json);
        var whole = jsonStringify(shared);
        if (whole.length > SHARED_MAX) {
          if (had) shared[k] = old; else delete shared[k];
          throw new ErrorC("useSharedState: the block's shared state would be " + whole.length + " characters, the limit is " + SHARED_MAX);
        }
        dirty = true;
        if (typeof hostShared === "function") hostShared(whole);
      };
      return st;
    });
    s.key = key;
    // The initial value is the code's until someone saved one; a new load re-reads it.
    if (s.ver !== codeVersion) {
      s.ver = codeVersion; s.initV = typeof init === "function" ? init() : init;
      if (sharedDefaultsVer !== codeVersion) { sharedDefaultsVer = codeVersion; sharedDefaults = {}; }
      sharedDefaults[key] = s.initV;
    }
    return [hasShared(key) ? shared[key] : s.initV, s.set];
  }
  // The host's saved object (JSON text). Returns the new tree, or "" when nothing changed
  // (or nothing is loaded yet).
  entry("__sharedLoad", function (json) {
    var next = jsonParse(json);
    if (!next || typeof next !== "object" || isArray(next)) return "";
    if (jsonStringify(next) === jsonStringify(shared)) return "";
    shared = next;
    agentRan();
    cur = null; dirty = false;
    return root ? jsonStringify(renderTree(false)) : "";
  });

  function useMemo(fn, deps) {
    var s = slot("memo", function () { return { deps: null, v: undefined, ver: -1 }; });
    if (s.ver !== codeVersion || depsChanged(s.deps, deps)) { s.v = fn(); s.deps = deps || null; s.ver = codeVersion; }
    return s.v;
  }
  function useCallback(fn, deps) { return useMemo(function () { return fn; }, deps); }
  function useRef(init) {
    var s = slot("ref", function () { return { ref: { current: init }, init: init, sig: signature(init), ver: codeVersion }; });
    if (s.ver !== codeVersion) {
      s.ver = codeVersion;
      var sig = signature(init);
      if (sig !== s.sig) { s.sig = sig; if (objectIs(s.ref.current, s.init)) s.ref.current = init; s.init = init; }
    }
    return s.ref;
  }
  function useEffect(fn, deps) {
    var s = slot("effect", function () { return { deps: null, cleanup: null, fn: null, ran: false }; });
    if (!s.ran || depsChanged(s.deps, deps)) { s.fn = fn; s.deps = deps || null; pendingEffects.push(s); }
  }
  // useReducedMotion(): the host's motion policy, true when motion is to be reduced. Not
  // state of the component: the same value on every render until the host changes it
  // (__motion renders again). A program draws its final / static pose when it is true
  // instead of waiting for a frame callback that will not come.
  function useReducedMotion() {
    if (!cur) throw new ErrorC("Hooks can only be called inside a component");
    return motionReduced;
  }
  function runCleanup(s) {
    var c = s.cleanup; s.cleanup = null;
    if (typeof c === "function") {
      agentRan();
      var before = origin; origin = "effect";
      try { c(); } finally { origin = before; }
    }
  }
  // Effects run synchronously right after the render that scheduled them.
  function flushEffects() {
    var list = pendingEffects; pendingEffects = [];
    if (list.length) agentRan();
    var before = origin; origin = "effect";
    try {
      for (var i = 0; i < list.length; i++) {
        var s = list[i];
        runCleanup(s);
        s.ran = true;
        s.cleanup = s.fn();
      }
    } finally { origin = before; }
  }
  function unmount(inst) {
    for (var i = 0; i < inst.hooks.length; i++) if (inst.hooks[i].kind === "effect") runCleanup(inst.hooks[i]);
  }
  function sweep() {
    mapForEach(instances, function (inst, path) {
      if (inst.gen !== gen) { unmount(inst); mapDelete(instances, path); }
    });
  }

  // ---- render: element tree -> plain { i, g, t, p, e, c } tree -------------
  // onClick -> "click"; null for a name that is not on + capital letter.
  function eventName(k) {
    var c = k.charCodeAt(2);
    return k.charCodeAt(0) === 111 && k.charCodeAt(1) === 110 && c >= 65 && c <= 90 ? k.charAt(2).toLowerCase() + k.slice(3) : null;
  }
  var events = null;         // the events of the props serialized last, or null
  function serializeProps(props, tag, id) {
    var p = {};
    events = null;
    for (var k in props) {
      if (k === "key" || k === "children") continue;
      var v = props[k], t = typeof v;
      if (t === "function") {
        var name = eventName(k), fnId;
        if (name === null) { note("PROP_DROPPED", "<" + tag + ">: the function in prop \"" + k + "\" is not an event handler (on...) and was left out", id); continue; }
        if (owner > 0) { mapSet(stable, --stableSeq, { f: v, e: refEpoch }); fnId = stableSeq; }
        else { building.push(v); fnId = building.length - 1; }
        if (events === null) events = {};
        events[name] = { h: fnId, e: runtimeEpoch };
      }
      else if (v && v.$evt === true) p[k] = StringC(v);
      else if (t === "string" || t === "number" || t === "boolean") p[k] = v;
      else if (v && t === "object" && !v.$el) {
        // Objects (e.g. style={{...}}) are passed as data so the host can count and drop them.
        try { p[k] = jsonParse(jsonStringify(v)); } catch (e) {}
      }
    }
    return p;
  }
  // "path" identifies the instance globally (hook state key); for a host element it is
  // the node id ("i") of the frame. "seg" is the same path but relative to the nearest
  // host element: a part that is sent as a reference must come again at the same one.
  function escapeKey(key) { return key.indexOf("~") < 0 && key.indexOf("/") < 0 ? key : key.replace(/~/g, "~0").replace(/\//g, "~1"); }
  function emit(node, path, seg, out, depth, hd) {
    if (node == null || typeof node === "boolean") return;
    if (typeof node === "string") { out.push(node); return; }
    if (typeof node === "number") { out.push(StringC(node)); return; }
    if (depth > MAX_DEPTH) throw new ErrorC("UI tree is too deep");
    if (isArray(node)) {
      var used = {};
      for (var i = 0; i < node.length; i++) {
        var child = node[i];
        // A segment is "n:<index>" for a child without a key and "k:<key>" for one with a
        // key, the key escaped as in a JSON Pointer ("~" -> "~0", "/" -> "~1"): no key
        // can spell another segment or a separator.
        var s = child && child.$el && child.key != null ? "k:" + escapeKey(child.key) : "n:" + i;
        // Duplicate keys (easy to hit mid-stream: key={s.na -> key={s}) would merge two
        // instances into one path; the later one gets the identity of its position.
        if (used[s] === true) { note("DUPLICATE_KEY", "Two children have the key \"" + StringC(child && child.key).slice(0, 60) + "\"; the later one is told apart by its position (under " + (path || "/") + ")"); s = "n:" + i; }
        used[s] = true;
        emit(child, path + "/" + s, seg === "" ? s : seg + "/" + s, out, depth, hd);
      }
      return;
    }
    if (node.$evt === true) { out.push(StringC(node)); return; }
    if (!node.$el) throw new ErrorC("Objects are not valid as a UI child");
    if (!delta) { emitElement(node, path, seg, out, depth, hd); return; }
    var s = node.$s, j;
    if (s !== undefined && s.e === refEpoch && s.seg === seg && s.path === path && s.hd === hd && s.depth === depth) {
      // The same element at the same place: what it rendered to is with the host.
      if ((nodeCount += s.n) > MAX_NODES) throw new ErrorC("UI tree is too large");
      for (j = 0; j < s.items.length; j++) out.push(s.items[j]);
      for (j = 0; j < s.insts.length; j++) { s.insts[j].gen = gen; instLog.push(s.insts[j]); }
      s.g = gen;
      refsSent++;
      return;
    }
    // An element out of a cell is likely to come again: its nodes get ids. (Not when it
    // already got them at another place in this pass.)
    if (node.$c !== true || (s !== undefined && s.e === refEpoch && s.g === gen)) { emitElement(node, path, seg, out, depth, hd); return; }
    var o0 = out.length, n0 = nodeCount, i0 = instLog.length;
    owner++;
    emitElement(node, path, seg, out, depth, hd);
    owner--;
    var items = [];
    for (j = o0; j < out.length; j++) {
      var it = out[j];
      if (typeof it === "string" || it.r !== undefined) items.push(it);
      else { if (it.d === undefined) { it.d = ++refSeq; refDefs++; } items.push({ r: it.d }); }
    }
    node.$s = { e: refEpoch, g: gen, path: path, seg: seg, hd: hd, depth: depth, n: nodeCount - n0, items: items, insts: instLog.slice(i0) };
    madeParts.push(node);
  }
  function emitElement(node, path, seg, out, depth, hd) {
    if (++nodeCount > MAX_NODES) throw new ErrorC("UI tree is too large");
    var type = node.type;
    if (type === FRAGMENT) { emit(node.children, path, seg, out, depth + 1, hd); return; }
    if (typeof type === "function") {
      var name = type.name || "Anonymous";
      var inst = mapGet(instances, path);
      if (!inst || inst.name !== name) {
        if (inst) unmount(inst);
        inst = { name: name, hooks: [], gen: gen };
        mapSet(instances, path, inst);
      }
      inst.gen = gen;
      if (delta) instLog.push(inst);
      var props = {};
      for (var k in node.props) if (k !== "key") props[k] = node.props[k];
      if (node.children.length) props.children = node.children.length === 1 ? node.children[0] : node.children;
      var prevCur = cur, prevIdx = hookIdx, res;
      cur = inst; hookIdx = 0;
      try { res = type(props); } finally { cur = prevCur; hookIdx = prevIdx; }
      emit(res, path + "/r", seg, out, depth + 1, hd);
      return;
    }
    // Host component marker ({ $host: "Button" }) or a lowercase tag; the host decides
    // whether the name is on its allow-list.
    var tag = typeof type === "string" ? type : type && typeof type.$host === "string" ? type.$host : "?";
    var c = [];
    emit(node.children, path, "", c, depth + 1, hd + 1);
    var o = { i: path, g: typeof type !== "string" ? "c" : PRIMITIVES[tag] === 1 ? "p" : "h", t: tag, p: serializeProps(node.props, tag, path) };
    if (events !== null) o.e = events;
    o.c = c;
    out.push(o);
  }

  // ---- entry points for the host -------------------------------------------
  // The runtime epoch of this guest instance: the session assigns it when it creates the
  // instance. A load does not change it.
  entry("__epoch", function (n) { runtimeEpoch = n; });
  entry("__defineHosts", function (json) {
    var names = jsonParse(json);
    // Capitalized names become globals; lower-case ones (svg elements) are plain JSX
    // strings and need none.
    for (var i = 0; i < names.length; i++) if (/^[A-Z]/.test(names[i])) G[names[i]] = freeze({ $host: names[i] });
  });

  // ---- timers and frames: a table of callbacks, run by the host's clock -------------
  var MIN_DELAY = 4, MAX_TIMERS = 64;
  var timers = new MapC();   // id -> { fn, at, every, raf, load }
  var nextTimerId = 1;
  var loading = 0;          // the module body is running (timers started now belong to this load): 1 whole program or a finished statement, 2 the unfinished tail
  var runningTimer = 0;     // id of the callback being run; still set if the tick was interrupted
  var droppedTimers = 0;
  // The host's motion policy (__motion): true = reduce. requestAnimationFrame is THE
  // animation queue of a program: every frame callback is decorative animation that may
  // be paused. While motion is reduced, frame callbacks stay in the table and are not
  // run; nothing is counted for them and nothing is replayed later: when motion comes
  // back each waiting callback runs once, on the clock as it is then. setTimeout and
  // setInterval are not touched (their purpose cannot be guessed).
  var motionReduced = false;
  function addTimer(fn, delay, every, raf) {
    if (typeof fn !== "function") return 0;
    var ms = NumberC(delay);
    if (!(ms >= MIN_DELAY)) ms = MIN_DELAY;
    var id = nextTimerId++;
    mapSet(timers, id, { fn: fn, at: raf ? 0 : hostNow() + ms, every: every ? ms : 0, raf: raf, load: loading });
    while (mapSize(timers) > MAX_TIMERS) { mapDelete(timers, mapFirstKey(timers)); droppedTimers++; }
    return id;
  }
  function clearTimer(id) { mapDelete(timers, id); }
  function reportDropped() {
    if (!droppedTimers) return;
    reportOnce("warn", "more than " + MAX_TIMERS + " pending timers: dropped the " + droppedTimers + " oldest", "timers-dropped");
    droppedTimers = 0;
  }
  G.setTimeout = function (fn, ms) { return addTimer(fn, ms, false, false); };
  G.setInterval = function (fn, ms) { return addTimer(fn, ms, true, false); };
  G.requestAnimationFrame = function (fn) { return addTimer(fn, 0, false, true); };
  G.clearTimeout = G.clearInterval = G.cancelAnimationFrame = clearTimer;
  G.performance = { now: function () { return hostNow(); } };

  // What the host's scheduler needs: is a frame callback pending, and when is the next timer due.
  function schedule() {
    var next = null, raf = false;
    mapForEach(timers, function (t) {
      if (t.raf) { if (!motionReduced) raf = true; }
      else if (next === null || t.at < next) next = t.at;
    });
    return { r: raf, n: next, c: mapSize(timers) };
  }
  entry("__schedule", function () { reportDropped(); return jsonStringify(schedule()); });
  // The reports that repeat, in the order they were first made, and how many did not fit.
  entry("__reports", function () {
    var list = [];
    mapForEach(reports, function (r) { list[list.length] = { l: r.l, m: r.m, n: r.n }; });
    return jsonStringify({ e: list, s: reportSuppressed });
  });

  // Runs what is due at nowMs. Returns { t: frame or null, r, n, c }: the frame only when a
  // callback changed state (one render for the whole tick).
  entry("__tick", function (nowMs) {
    if (runningTimer) { mapDelete(timers, runningTimer); runningTimer = 0; }
    cur = null; dirty = false;
    var due = [];
    mapForEach(timers, function (t, id) { if (t.raf ? !motionReduced : t.at <= nowMs) due.push(id); });
    if (due.length) agentRan();
    for (var i = 0; i < due.length; i++) {
      var id = due[i], t = mapGet(timers, id);
      if (!t) continue; // cleared by an earlier callback of this tick
      if (t.every) t.at = nowMs + t.every; // one call however many periods were missed
      else mapDelete(timers, id);
      runningTimer = t.every ? id : 0;
      origin = "timer";
      try { t.fn(t.raf ? nowMs : undefined); }
      catch (e) { reportOnce("error", "timer callback: " + (e && e.message ? e.message : StringC(e))); }
      origin = "none";
      runningTimer = 0;
    }
    var tree = dirty && root ? renderTree() : null;
    reportDropped();
    var s = schedule();
    s.t = tree;
    return jsonStringify(s);
  });

  // A capitalized name used as a JSX tag that is not defined anywhere (an unknown
  // <Iframe>) resolves to a host marker too instead of throwing a ReferenceError, so the
  // host can render an "Unknown component" notice in place and keep the rest of the tree.
  // Agent code runs inside with(scope); only those tag names are answered by the proxy.
  var scope = new ProxyC({}, {
    has: function (_, k) { return typeof k === "string" && jsxNames[k] === true && !(k in G); },
    get: function (_, k) { return typeof k === "string" ? { $host: k } : undefined; }
  });
  function requireStub(name) {
    throw new ErrorC('import is not supported in generated UI code (tried to import "' + name +
      '"). React hooks and all components are available as globals.');
  }

  // compiledCode is CommonJS-style output of sucrase: "export default X" became
  // "exports.default = X" and "import" became require(), which we reject.
  // requireDefault (streaming): a prefix of the program may only contain helper
  // components so far; rendering one of them as the root would flash a wrong UI.
  entry("__load", function (code, requireDefault) {
    var m, tags = {}, tagRe = /\b(?:__)?h\(\s*([A-Z][\w$]*)\s*[,)]/g;
    while ((m = tagRe.exec(code))) tags[m[1]] = true;
    jsxNames = tags;
    var names = [], re = /^[ \t]?(?:async[ \t]+)?(?:function[ \t]+|(?:const|let|var)[ \t]+)([A-Z][\w$]*)/gm;
    while ((m = re.exec(code))) names.push(m[1]);
    var tail = "\n;return [" + names.map(function (n) {
      return "typeof " + n + " === 'function' ? " + n + " : undefined";
    }).join(",") + "];";
    var exportsObj = {}, moduleObj = { exports: exportsObj };
    var fn = new FunctionC("exports", "module", "require", "__scope", "with (__scope) {\n" + code + tail + "\n}");
    // Timers the previous run of the module body started are this body's to start again.
    mapForEach(timers, function (t, id) { if (t.load) mapDelete(timers, id); });
    var cands;
    loading = 1; origin = "module";
    try { cands = fn(exportsObj, moduleObj, requireStub, scope); } finally { loading = 0; origin = "none"; }
    var next = typeof exportsObj.default === "function" ? exportsObj.default : null;
    // No default export: the LAST top-level capitalized function is the root.
    if (!next && requireDefault) throw new ErrorC("Waiting for the default export");
    for (var i = cands.length - 1; !next && i >= 0; i--) if (cands[i]) next = cands[i];
    if (!next) throw new ErrorC("No root component: export default a function component");
    root = next;
    codeVersion++;
    // Every value of the program is new: nothing a cell holds can be compared.
    agentRan();
  });

  // ---- a program that arrives statement by statement ------------------------------
  // While a block streams, its top-level statements arrive one after the other and only
  // the last can still change. __loadParts runs each finished statement ONCE and the
  // unfinished tail on every call. Each part is a function of its own; the names it
  // declares at the top level are copied into "mod", which every part sees through
  // with (mod). So a later part (and the tail, run again and again) finds the constants
  // and components of the earlier ones without their code being evaluated again.
  // (One difference to a single function body: a name declared in one statement and
  // reassigned by a closure of the SAME statement is not seen changing by the others.
  // The finished block is loaded whole by __load, where that cannot happen.)
  var mod = {}, modExports = {}, modOrder = [], modTags = {}, tailNames = [];
  var tagRe2 = /\b(?:__)?h\(\s*([A-Z][\w$]*)\s*[,)]/g;
  function runPart(part, kind, tags) {
    var m, names = part.n, ret = [];
    tagRe2.lastIndex = 0;
    while ((m = tagRe2.exec(part.c))) tags[m[1]] = true;
    for (var i = 0; i < names.length; i++) ret.push(jsonStringify(names[i]) + ":typeof " + names[i] + "==='undefined'?undefined:" + names[i]);
    var fn = new FunctionC("exports", "module", "require", "__scope", "__mod",
      "with (__scope) { with (__mod) {\n" + part.c + "\n;return {" + ret.join(",") + "};\n} }");
    var vals;
    loading = kind; origin = "module";
    try { vals = fn(modExports, { exports: modExports }, requireStub, scope, mod); } finally { loading = 0; origin = "none"; }
    for (var j = 0; j < names.length; j++) {
      mod[names[j]] = vals[names[j]];
      if (modOrder.indexOf(names[j]) < 0) modOrder.push(names[j]);
    }
  }
  // reset: 1 = a new program starts (forget every part). appendedJson / tailJson: arrays
  // of { c: code, n: [top-level names] }. An error of a finished statement is reported
  // with the prefix "finished statement: " (the host then goes back to __load).
  // stale: 1 = a finished statement reads a top-level name that is declared only now
  // (its cells did not compare it): forget what the cells hold.
  // cellsJson: array of { i: id, p: [parameter names], c: code } for the cells the parts
  // call by id. Each becomes a function once; like the parts it sees the names of the
  // program through with (mod).
  entry("__loadParts", function (reset, appendedJson, tailJson, requireDefault, stale, cellsJson) {
    var appended = jsonParse(appendedJson), tail = jsonParse(tailJson), i;
    if (reset || stale) agentRan();
    if (reset) cellFns = {};
    if (reset) {
      mod = {}; modExports = {}; modOrder = []; modTags = {}; tailNames = [];
      mapForEach(timers, function (t, id) { if (t.load) mapDelete(timers, id); });
    }
    // What the previous run of the tail declared and started is this run's to do again.
    mapForEach(timers, function (t, id) { if (t.load === 2) mapDelete(timers, id); });
    for (i = 0; i < tailNames.length; i++) delete mod[tailNames[i]];
    tailNames = [];
    var defs = cellsJson ? jsonParse(cellsJson) : [], m;
    for (i = 0; i < defs.length; i++) {
      tagRe2.lastIndex = 0;
      while ((m = tagRe2.exec(defs[i].c))) modTags[m[1]] = true;
      cellFns[defs[i].i] = new FunctionC("__scope", "__mod",
        "with (__scope) { with (__mod) { return function (" + defs[i].p.join(",") + ") { return " + defs[i].c + "\n; }; } }")(scope, mod);
    }
    var tags = {};
    for (var k in modTags) tags[k] = true;
    jsxNames = tags;
    for (i = 0; i < appended.length; i++) {
      try { runPart(appended[i], 1, modTags); }
      catch (e) { throw new ErrorC("finished statement: " + (e && e.message ? e.message : StringC(e))); }
    }
    for (k in modTags) tags[k] = true;
    for (i = 0; i < tail.length; i++) {
      for (var j = 0; j < tail[i].n.length; j++) tailNames.push(tail[i].n[j]);
      runPart(tail[i], 2, tags);
    }
    var next = typeof modExports.default === "function" ? modExports.default : null;
    if (!next && requireDefault) throw new ErrorC("Waiting for the default export");
    for (i = modOrder.length - 1; !next && i >= 0; i--) {
      if (/^[A-Z]/.test(modOrder[i]) && typeof mod[modOrder[i]] === "function") next = mod[modOrder[i]];
    }
    if (!next) throw new ErrorC("No root component: export default a function component");
    root = next;
    codeVersion++;
  });

  function renderTree(full) {
    if (!root) throw new ErrorC("Nothing loaded");
    var out, loops = 0, epoch = 0, stable0 = stableSeq;
    // The host lost track (full), or most of the ids it holds are no longer in the tree.
    if (delta && (full || refDefs > 4 * lastNodes + 512)) newRefEpoch();
    try {
      do {
        // A second pass: state was set while rendering or by an effect.
        if (loops > 0) agentRan();
        dirty = false; cur = null; hookIdx = 0; gen++; building = []; pendingEffects = []; nodeCount = 0;
        owner = 0; instLog = []; madeParts = []; notes = [];
        epoch = refEpoch;
        out = [];
        origin = "render";
        emit([h(root, null)], "", "", out, 0, 0);
        origin = "none";
        sweep();
        flushEffects(); // a setState inside an effect sets dirty -> one more pass (max 10)
      } while (dirty && ++loops < 10);
    } catch (e) {
      // Keep the previous program alive so handlers of the tree still on screen keep working.
      cur = null; root = goodRoot; origin = "none";
      // The ids given in this render never reach the host: take them back. What the
      // host was sent before stays valid (a prefix that fails keeps the tree on screen).
      for (var j = 0; j < madeParts.length; j++) madeParts[j].$s = undefined;
      madeParts = [];
      for (j = stable0 - 1; j >= stableSeq; j--) mapDelete(stable, j);
      throw e;
    }
    goodRoot = root;
    handlers = building;
    lastNodes = nodeCount;
    if (delta && stableSwept !== epoch) {
      // The tree that goes out holds no handler of an earlier refEpoch.
      stableSwept = epoch;
      mapForEach(stable, function (h, id) { if (h.e !== epoch) mapDelete(stable, id); });
    }
    var frame = { protocol: PROTOCOL, runtimeEpoch: runtimeEpoch, frameRevision: ++frameSeq };
    if (delta) {
      // The frame is based on the reference frame that came out before it in this epoch;
      // the first frame of an epoch is a full rebuild (base 0, no reference in it).
      frame.references = { epoch: epoch, baseFrameRevision: refOutEpoch === epoch ? refOutFrame : 0 };
      refOutEpoch = epoch; refOutFrame = frameSeq;
    }
    if (notes.length) frame.diagnostics = notes;
    frame.root = out;
    return frame;
  }
  // The host's motion policy changed. Returns the new tree (the program reads it with
  // useReducedMotion), or "" when it is the same policy or nothing is loaded.
  entry("__motion", function (reduce) {
    var next = reduce === 1;
    if (next === motionReduced) return "";
    motionReduced = next;
    agentRan();
    cur = null; dirty = false;
    return root ? jsonStringify(renderTree(false)) : "";
  });
  entry("__render", function (full) { return jsonStringify(renderTree(full === 1)); });
  // on: 1 = the host keeps the nodes it was sent with "d": id and takes { r: id } for them.
  entry("__delta", function (on) { delta = on === 1; newRefEpoch(); });
  entry("__cellStats", function () { return jsonStringify({ hits: cellHits, misses: cellMisses, refs: refsSent, cellEpoch: cellEpoch, refEpoch: refEpoch }); });

  function noop() {}
  function makeEvent(d) {
    var value = typeof d.value === "string" ? d.value : undefined;
    var checked = typeof d.checked === "boolean" ? d.checked : undefined;
    var target = { value: value, checked: checked };
    var e = {
      $evt: true, target: target, currentTarget: target, value: value, checked: checked,
      preventDefault: noop, stopPropagation: noop,
      toString: function () { return value === undefined ? StringC(checked) : value; }
    };
    // What the host copied off the DOM event (open mode): type, key, pointer position ...
    for (var k in d) if (!(k in e)) { var v = d[k]; if (typeof v === "string" || typeof v === "number" || typeof v === "boolean") e[k] = v; }
    return e;
  }

  entry("__dispatch", function (id, argsJson) {
    var held = id < 0 ? mapGet(stable, id) : undefined;
    var f = id < 0 ? (held ? held.f : undefined) : (hasOwn(handlers, id) ? handlers[id] : undefined);
    if (typeof f !== "function") return "";
    cur = null; dirty = false;
    agentRan();
    var args = jsonParse(argsJson);
    for (var i = 0; i < args.length; i++) {
      if (args[i] && typeof args[i] === "object" && args[i].$event) args[i] = makeEvent(args[i].$event);
    }
    // The one place agent code runs as "event": the handler itself, not the render or
    // the effects that follow, and not a timer it starts.
    origin = "event";
    try { rApply(f, undefined, args); } finally { origin = "none"; }
    return dirty ? jsonStringify(renderTree(false)) : "";
  });

  // raft.reply / raft.openLink: the host's two actions. Only an event handler may ask.
  (function () {
    var given = G.raft || {}, hostReply = given.reply, hostOpenLink = given.openLink;
    function action(name, host) {
      return function (value) {
        if (origin !== "event") { denied(name, value); return; }
        if (typeof host === "function") host(value);
      };
    }
    defineProp(G, "raft", { value: freeze({ reply: action("reply", hostReply), openLink: action("openLink", hostOpenLink) }), writable: false, configurable: false, enumerable: true });
  })();
  // Capabilities this runtime does not have are named, so that a program that uses one
  // fails with a clear message instead of a ReferenceError.
  G.useAction = function () { throw new ErrorC("UNSUPPORTED_CAPABILITY: useAction is not available in this host"); };

  // ---- globals for agent code ----------------------------------------------
  function fmt(args) {
    var parts = [];
    for (var i = 0; i < args.length; i++) {
      var a = args[i];
      if (typeof a === "string") parts.push(a);
      else { try { parts.push(jsonStringify(a)); } catch (e) { parts.push(StringC(a)); } }
    }
    return parts.join(" ").slice(0, 2000);
  }
  function logger(level) { return function () { hostLog(level, fmt(arguments)); }; }
  G.console = { log: logger("log"), info: logger("log"), warn: logger("warn"), error: logger("error"), debug: logger("log") };

  var hooks = { useState: useState, useSharedState: useSharedState, useReducer: useReducer, useMemo: useMemo, useCallback: useCallback, useRef: useRef, useEffect: useEffect, useReducedMotion: useReducedMotion };
  G.h = h;
  // What compiled JSX calls (the compiler emits these two names): not h / Fragment,
  // which a program may use for a variable of its own: items.map((h) => <Row />).
  G.__h = h;
  G.__F = FRAGMENT;
  G.__c = cell;
  G.__f = tagFunction;
  G.Fragment = FRAGMENT;
  G.React = { createElement: h, Fragment: FRAGMENT };
  for (var name in hooks) { G[name] = hooks[name]; G.React[name] = hooks[name]; }
})(globalThis);
