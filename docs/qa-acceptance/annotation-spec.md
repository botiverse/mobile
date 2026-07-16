# Evidence Annotation Spec — Sharp Red-Box Highlights

> After each screenshot, the executor post-processes with `sharp` (Node.js)
> to draw visual annotations. This helps reviewers instantly see what the
> agent was verifying and where it was looking.

## What to Annotate

### 1. Assertion Region (green or red box)
After the agent evaluates an assertion on a screenshot, draw a box around
the UI region the assertion targets:
- **Green box** = assertion passed (element found, text matched, etc.)
- **Red box** = assertion failed (element missing, text mismatch, etc.)

### 2. Tap Point (crosshair)
Draw a small crosshair (✕) at the exact coordinates the agent tapped.
Helps reviewers see if the tap hit the right target.

### 3. Input Field Highlight (dashed box)
Draw a dashed yellow box around the field the agent typed into.

## Implementation (runner post-processing)

```javascript
const sharp = require('sharp');

async function annotateScreenshot(inputPath, outputPath, annotations) {
  let svgOverlay = '';
  for (const ann of annotations) {
    if (ann.type === 'assertion_pass') {
      svgOverlay += `<rect x="${ann.x}" y="${ann.y}" width="${ann.w}" height="${ann.h}" 
        fill="none" stroke="#00ff00" stroke-width="4" rx="4"/>`;
    } else if (ann.type === 'assertion_fail') {
      svgOverlay += `<rect x="${ann.x}" y="${ann.y}" width="${ann.w}" height="${ann.h}" 
        fill="none" stroke="#ff0000" stroke-width="4" rx="4"/>`;
    } else if (ann.type === 'tap') {
      const s = 20;
      svgOverlay += `<line x1="${ann.x-s}" y1="${ann.y-s}" x2="${ann.x+s}" y2="${ann.y+s}" 
        stroke="#ffff00" stroke-width="3"/>
        <line x1="${ann.x-s}" y1="${ann.y+s}" x2="${ann.x+s}" y2="${ann.y-s}" 
        stroke="#ffff00" stroke-width="3"/>`;
    } else if (ann.type === 'input_field') {
      svgOverlay += `<rect x="${ann.x}" y="${ann.y}" width="${ann.w}" height="${ann.h}" 
        fill="none" stroke="#ffff00" stroke-width="3" stroke-dasharray="8,4" rx="4"/>`;
    }
  }
  
  const svg = Buffer.from(
    `<svg width="${width}" height="${height}">${svgOverlay}</svg>`
  );
  
  await sharp(inputPath)
    .composite([{ input: svg, top: 0, left: 0 }])
    .png()
    .toFile(outputPath);
}
```

## File Naming Convention

- `step-NN.png` — raw screenshot (unchanged)
- `step-NN-annotated.png` — annotated version (boxes, crosshairs)
- Review UI shows annotated by default, raw available on click

## Case Schema Enhancement (v0.3)

Assertions can now specify hint regions for annotation:

```yaml
assertions:
  - id: message-appears
    goal: "Sent message appears in conversation"
    type: text_present
    after_step: verify-sent
    severity: critical
    highlight:
      description: "The sent message in the conversation list"
      # Agent auto-detects bounds from uiautomator dump; this is just a hint
      search_text: "QA test message"
```

The agent uses the `highlight.search_text` to find the element in the
uiautomator XML dump, extract bounds, and draw the annotation box.
