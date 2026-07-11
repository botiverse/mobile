#!/usr/bin/env python3
"""visual_verify — routine pixel-verification for the visual-fix pipeline.

Codifies the mechanical measurements a verification round does, so simple
blades can be checked by script / M3 without a human agent. The JUDGMENT parts
(which targets to assert, interpreting anomalies, provenance, root-cause) stay
with the reviewing agent — this tool just produces the numbers + pass/fail
against an explicit target spec.

Inputs (a small JSON spec on stdin or --spec file):
  {
    "case": "components.ui.slug-input.states",
    "after":  {"run": 29143252272} | {"img": "/path/after.png"},
    "before": "gh-pages" | {"img": "..."} | null,   # gh-pages latest/android
    "react":  "gh-pages" | {"img": "..."} | null,   # gh-pages latest/react
    "density": 3.0,                                   # 480dpi CI emulator
    "region": "bottom-half" | "full" | [x0,y0,x1,y1],
    "colors": [{"name":"tile","hex":"FFF7DD","tol":6,"min_count":50}, ...],
    "diff":   true,          # before->after changed bbox + top transitions
    "glyph_center": {"container":"cream-tile-left","tol_dp":0.5}  # optional
  }

Output: JSON with measurements + per-check PASS/FAIL + overall verdict.
Provenance note: `after.run` records the sha CI built from — always verify the
measured build is the PR's pinned gitlink, never an ambient artifact.
"""
import json, sys, os, subprocess, io, base64

def sh(*a):
    return subprocess.run(a, capture_output=True, text=True)

def load_img(path):
    from PIL import Image
    return Image.open(path).convert("RGB")

def dl_run_android(run, case, dst):
    """Download <case>.png from a run's android shard-0 artifact."""
    os.makedirs(dst, exist_ok=True)
    sh("gh","run","download",str(run),"--repo","botiverse/mobile",
       "-n","visual-results-android-shard-0","-D",dst)
    p = os.path.join(dst, f"{case}.png")
    return p if os.path.exists(p) else None

def ghpages_img(kind, case, dst):
    """Pull latest/<kind>/<case>.png from gh-pages (kind = android|react). Binary-safe."""
    rb = subprocess.run(["git","-c","submodule.recurse=false","show",
        f"origin/gh-pages:visual-testing/latest/{kind}/{case}.png"],
        capture_output=True)
    if rb.returncode != 0 or not rb.stdout: return None
    p = os.path.join(dst, f"{kind}_{case}.png")
    open(p,"wb").write(rb.stdout); return p

def resolve(src, case, kind, dst):
    if src is None: return None
    if isinstance(src, dict):
        if "img" in src: return src["img"]
        if "run" in src: return dl_run_android(src["run"], case, dst)
    if src == "gh-pages": return ghpages_img(kind, case, dst)
    return None

def region_box(region, W, H):
    if region == "full" or region is None: return (0,0,W,H)
    if region == "bottom-half": return (0,H//2,W,H)
    if region == "top-half": return (0,0,W,H//2)
    return tuple(region)

def close(c,t,tol): return all(abs(c[i]-t[i])<=tol for i in range(3))

def check_colors(img, region, colors):
    px=img.load(); W,H=img.size; x0,y0,x1,y1=region_box(region,W,H)
    out=[]
    for spec in colors:
        t=tuple(int(spec["hex"][i:i+2],16) for i in (0,2,4))
        tol=spec.get("tol",6); n=0; sample=None
        for y in range(y0,y1):
            for x in range(x0,x1):
                if close(px[x,y],t,tol):
                    n+=1
                    if sample is None: sample=px[x,y]
        ok = n>=spec.get("min_count",1)
        out.append({"name":spec["name"],"target":"#"+spec["hex"].upper(),
                    "count":n,"sample":sample,"pass":ok})
    return out

def check_diff(before, after):
    from PIL import ImageChops
    if before.size!=after.size:
        return {"size_before":before.size,"size_after":after.size,"note":"SIZE CHANGED"}
    bb=ImageChops.difference(before,after).getbbox()
    if bb is None: return {"changed":0,"bbox":None,"transitions":[]}
    pb,pa=before.load(),after.load(); W,H=after.size
    from collections import Counter
    tr=Counter(); n=0
    for y in range(H):
        for x in range(W):
            if pb[x,y]!=pa[x,y]:
                n+=1; tr[(pb[x,y],pa[x,y])]+=1
    top=[{"from":"#%02X%02X%02X"%bc,"to":"#%02X%02X%02X"%ac,"count":k}
         for (bc,ac),k in tr.most_common(6)]
    return {"changed":n,"bbox":bb,"transitions":top}

def measure_glyph_center(img, density):
    """Left cream-tile in the disabled (bottom) row: glyph center vs tile center."""
    px=img.load(); W,H=img.size
    def cream(c): return c[0]>238 and 228<c[1]<252 and 200<c[2]<240
    cp=[(x,y) for y in range(H//2,H) for x in range(0,150) if cream(px[x,y])]
    if not cp: return None
    xs=[p[0] for p in cp]; ys=[p[1] for p in cp]
    tx0,tx1,ty0,ty1=min(xs),max(xs),min(ys),max(ys)
    tile_cy=(ty0+ty1)/2; int_h=ty1-ty0+1
    def glyph(c): return 120<sum(c)/3<185 and abs(c[0]-c[2])<48
    gp=[(x,y) for y in range(ty0,ty1+1) for x in range(tx0,tx1+1)
        if glyph(px[x,y]) and not cream(px[x,y])]
    if not gp: return {"tile_interior_dp":round(int_h/density,2),"glyph":None}
    gys=[p[1] for p in gp]; gcy=(min(gys)+max(gys))/2
    return {"tile_interior_dp":round(int_h/density,2),
            "glyph_vs_tile_center_dp":round((gcy-tile_cy)/density,2)}

def main():
    spec = json.load(open(sys.argv[sys.argv.index("--spec")+1])) if "--spec" in sys.argv else json.load(sys.stdin)
    case=spec["case"]; density=spec.get("density",3.0)
    dst=f"/tmp/vv_{case}"; os.makedirs(dst,exist_ok=True)
    after=resolve(spec["after"],case,"android",dst)
    before=resolve(spec.get("before"),case,"android",dst)
    react=resolve(spec.get("react"),case,"react",dst)
    res={"case":case,"after_img":after,"provenance_note":
         "confirm 'after' build == PR pinned gitlink sha, not an ambient artifact"}
    if not after: print(json.dumps({"error":"no after image","spec":spec})); sys.exit(1)
    aimg=load_img(after)
    if spec.get("colors"):
        res["colors"]=check_colors(aimg,spec.get("region"),spec["colors"])
    if spec.get("diff") and before:
        res["diff"]=check_diff(load_img(before),aimg)
    if spec.get("glyph_center"):
        res["glyph_center_android"]=measure_glyph_center(aimg,density)
        if react: res["glyph_center_react"]=measure_glyph_center(load_img(react),density)
    checks=[]
    if "colors" in res: checks+= [c["pass"] for c in res["colors"]]
    res["verdict"]="PASS" if checks and all(checks) else ("REVIEW" if not checks else "FAIL")
    print(json.dumps(res,indent=2,default=str))

if __name__=="__main__":
    main()
