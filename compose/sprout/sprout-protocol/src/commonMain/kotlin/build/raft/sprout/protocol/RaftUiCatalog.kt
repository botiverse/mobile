package build.raft.sprout.protocol

const val RAFT_UI_CATALOG_ID: String = "raft-ui"
const val RAFT_UI_VERSION: String = "0.102.2"
const val RAFT_UI_CATALOG_VERSION: String = "$RAFT_UI_VERSION+2"
const val RAFT_UI_INPUT_VALUE_MAX: Int = 4000
const val RAFT_UI_MAX_BOX_WIDTH: Double = 1200.0
const val RAFT_UI_MAX_BOX_HEIGHT: Double = 1600.0
const val RAFT_UI_MAX_SVG_TEXT: Int = 4000

object RaftUiCatalog {
    val GAPS: List<String> = listOf("none", "xs", "sm", "md", "lg")
    val ALIGN: List<String> = listOf("start", "center", "end", "stretch")
    val JUSTIFY: List<String> = listOf("start", "center", "end", "between")
    val BUTTON_VARIANTS: List<String> = listOf(
        "default", "primary", "accent", "outline", "ghost", "muted",
        "success", "warning", "danger", "information", "danger-secondary", "danger-outline",
    )
    val BUTTON_SIZES: List<String> = listOf("xs", "sm", "md", "lg", "xl")
    val BADGE_VARIANTS: List<String> = listOf(
        "default", "primary", "information", "muted", "accent", "success", "warning", "danger",
    )
    val BADGE_APPEARANCE: List<String> = listOf("solid", "soft", "outline")
    val INPUT_TYPES: List<String> = listOf("text", "email", "number", "password", "search", "tel", "url")
    val TABS_VARIANTS: List<String> = listOf("default", "underline")
    val PROGRESS_VARIANTS: List<String> = listOf("primary", "information", "accent", "success", "warning", "danger")
    val PROGRESS_SIZES: List<String> = listOf("sm", "md", "lg")
    val CELL_ALIGN: List<String> = listOf("left", "right")
    val AVATAR_SIZES: List<String> = listOf("xs", "sm", "md", "lg")
    val AVATAR_TYPES: List<String> = listOf("human", "agent", "server")
    val SEPARATOR_ORIENTATION: List<String> = listOf("horizontal", "vertical")
    val LINK_VARIANTS: List<String> = listOf("muted", "strong")

    private const val NUMBER_LIST_PATTERN = "^[\\d\\s,.\\-+eE%]*$"
    private const val FONT_WEIGHT_PATTERN = "^(?:normal|bold|bolder|lighter|[1-9]00)$"

    private fun en(vararg values: String): PropSchema = PropSchema.EnumVal(values.toSet())
    private fun en(values: List<String>): PropSchema = PropSchema.EnumVal(values.toSet())
    private fun str(max: Int = ProtocolLimits.DEFAULT_STRING_CHARS): PropSchema = PropSchema.Str(max)
    private fun num(min: Double, max: Double): PropSchema = PropSchema.Num(min, max)

    private val motionProps: Map<String, PropSchema> = linkedMapOf(
        "opacity" to num(0.0, 1.0),
        "transform" to PropSchema.Transform(TransformTarget.CSS),
    )

    private val sizeProps: Map<String, PropSchema> = linkedMapOf(
        "width" to num(0.0, RAFT_UI_MAX_BOX_WIDTH),
        "height" to num(0.0, RAFT_UI_MAX_BOX_HEIGHT),
        "minHeight" to num(0.0, RAFT_UI_MAX_BOX_HEIGHT),
    )

    private val clickEvents: Set<String> = setOf("click")

    val COMPONENTS: Map<String, ComponentSpec> = linkedMapOf(
        "Stack" to ComponentSpec(
            props = LinkedHashMap<String, PropSchema>().apply {
                put("gap", en(GAPS))
                put("align", en(ALIGN))
                putAll(motionProps)
                putAll(sizeProps)
            },
        ),
        "Row" to ComponentSpec(
            props = LinkedHashMap<String, PropSchema>().apply {
                put("gap", en(GAPS))
                put("align", en(ALIGN))
                put("justify", en(JUSTIFY))
                put("wrap", PropSchema.Bool)
                putAll(motionProps)
                putAll(sizeProps)
            },
        ),
        "Grid" to ComponentSpec(
            props = LinkedHashMap<String, PropSchema>().apply {
                put("columns", num(1.0, 4.0))
                put("gap", en(GAPS))
                putAll(motionProps)
            },
        ),
        "Box" to ComponentSpec(
            props = LinkedHashMap<String, PropSchema>().apply {
                put("clip", PropSchema.Bool)
                putAll(motionProps)
                putAll(sizeProps)
            },
        ),
        "Text" to ComponentSpec(
            props = linkedMapOf(
                "size" to en("small", "body", "title"),
                "tone" to en("default", "muted", "danger", "success"),
                "weight" to en("regular", "bold"),
            ),
        ),
        "Heading" to ComponentSpec(
            props = linkedMapOf("level" to num(1.0, 3.0)),
        ),
        "Divider" to ComponentSpec(
            props = emptyMap(),
            acceptsChildren = false,
        ),
        "Button" to ComponentSpec(
            props = linkedMapOf(
                "variant" to en(BUTTON_VARIANTS),
                "size" to en(BUTTON_SIZES),
                "disabled" to PropSchema.Bool,
            ),
            events = clickEvents,
        ),
        "Badge" to ComponentSpec(
            props = linkedMapOf(
                "variant" to en(BADGE_VARIANTS),
                "appearance" to en(BADGE_APPEARANCE),
            ),
        ),
        "Card" to ComponentSpec(props = emptyMap()),
        "CardHeader" to ComponentSpec(props = emptyMap()),
        "CardTitle" to ComponentSpec(props = emptyMap()),
        "CardDescription" to ComponentSpec(props = emptyMap()),
        "CardContent" to ComponentSpec(props = emptyMap()),
        "CardFooter" to ComponentSpec(props = emptyMap()),
        "Input" to ComponentSpec(
            props = linkedMapOf(
                "label" to str(120),
                "value" to str(RAFT_UI_INPUT_VALUE_MAX),
                "placeholder" to str(200),
                "type" to en(INPUT_TYPES),
                "disabled" to PropSchema.Bool,
            ),
            controlledProps = setOf("value"),
            events = setOf("change"),
            acceptsChildren = false,
        ),
        "Checkbox" to ComponentSpec(
            props = linkedMapOf(
                "label" to str(200),
                "checked" to PropSchema.Bool,
                "disabled" to PropSchema.Bool,
            ),
            controlledProps = setOf("checked"),
            events = setOf("checkedChange"),
            acceptsChildren = false,
        ),
        "Switch" to ComponentSpec(
            props = linkedMapOf(
                "label" to str(200),
                "checked" to PropSchema.Bool,
                "disabled" to PropSchema.Bool,
            ),
            controlledProps = setOf("checked"),
            events = setOf("checkedChange"),
            acceptsChildren = false,
        ),
        "Tabs" to ComponentSpec(
            props = linkedMapOf(
                "value" to str(80),
                "defaultValue" to str(80),
            ),
            controlledProps = setOf("value"),
            events = setOf("valueChange"),
        ),
        "TabsList" to ComponentSpec(
            props = linkedMapOf("variant" to en(TABS_VARIANTS)),
        ),
        "TabsTab" to ComponentSpec(
            props = linkedMapOf(
                "value" to str(80),
                "disabled" to PropSchema.Bool,
            ),
        ),
        "TabsPanel" to ComponentSpec(
            props = linkedMapOf("value" to str(80)),
        ),
        "Progress" to ComponentSpec(
            props = linkedMapOf(
                "value" to num(0.0, 1e9),
                "max" to num(1.0, 1e9),
                "label" to str(120),
                "showValue" to PropSchema.Bool,
                "variant" to en(PROGRESS_VARIANTS),
                "size" to en(PROGRESS_SIZES),
            ),
            acceptsChildren = false,
        ),
        "Table" to ComponentSpec(props = emptyMap()),
        "TableHeader" to ComponentSpec(props = emptyMap()),
        "TableBody" to ComponentSpec(props = emptyMap()),
        "TableRow" to ComponentSpec(props = emptyMap()),
        "TableHead" to ComponentSpec(props = linkedMapOf("align" to en(CELL_ALIGN))),
        "TableCell" to ComponentSpec(props = linkedMapOf("align" to en(CELL_ALIGN))),
        "Avatar" to ComponentSpec(
            props = linkedMapOf(
                "name" to str(80),
                "size" to en(AVATAR_SIZES),
                "type" to en(AVATAR_TYPES),
            ),
            acceptsChildren = false,
        ),
        "Separator" to ComponentSpec(
            props = linkedMapOf("orientation" to en(SEPARATOR_ORIENTATION)),
            acceptsChildren = false,
        ),
        "Link" to ComponentSpec(
            props = linkedMapOf(
                "href" to PropSchema.Url,
                "variant" to en(LINK_VARIANTS),
            ),
            events = clickEvents,
        ),
    )

    private val svgUnit = num(0.0, 1.0)
    private val svgTransform = PropSchema.Transform(TransformTarget.SVG)

    private val svgPresentation: Map<String, PropSchema> = linkedMapOf(
        "fill" to PropSchema.Paint,
        "stroke" to PropSchema.Paint,
        "strokeWidth" to PropSchema.Length,
        "strokeLinecap" to en("butt", "round", "square"),
        "strokeLinejoin" to en("miter", "round", "bevel"),
        "strokeDasharray" to PropSchema.Pattern(NUMBER_LIST_PATTERN, 200),
        "strokeDashoffset" to PropSchema.Length,
        "opacity" to svgUnit,
        "fillOpacity" to svgUnit,
        "strokeOpacity" to svgUnit,
        "fillRule" to en("nonzero", "evenodd"),
        "clipRule" to en("nonzero", "evenodd"),
        "transform" to svgTransform,
        "id" to PropSchema.Id,
        "clipPath" to PropSchema.IdRef,
        "mask" to PropSchema.IdRef,
    )

    private val svgTextProps: Map<String, PropSchema> = linkedMapOf(
        "x" to PropSchema.Length,
        "y" to PropSchema.Length,
        "fontSize" to PropSchema.Length,
        "fontWeight" to PropSchema.Pattern(FONT_WEIGHT_PATTERN, 10),
        "textAnchor" to en("start", "middle", "end"),
        "dominantBaseline" to en(
            "auto", "middle", "central", "hanging", "alphabetic",
            "ideographic", "mathematical", "text-before-edge", "text-after-edge",
        ),
    )

    private val svgGradientProps: Map<String, PropSchema> = linkedMapOf(
        "id" to PropSchema.Id,
        "gradientUnits" to en("userSpaceOnUse", "objectBoundingBox"),
        "gradientTransform" to svgTransform,
    )

    private fun svgShape(geometry: Map<String, PropSchema>): ComponentSpec = ComponentSpec(
        props = LinkedHashMap<String, PropSchema>().apply {
            putAll(geometry)
            putAll(svgPresentation)
        },
        events = clickEvents,
        svg = SvgPlacement.CHILD,
    )

    private fun svgChild(props: Map<String, PropSchema>): ComponentSpec = ComponentSpec(
        props = props,
        svg = SvgPlacement.CHILD,
    )

    val SVG_EXTRAS: Map<String, ComponentSpec> = linkedMapOf(
        "ellipse" to svgShape(
            linkedMapOf(
                "cx" to PropSchema.Length,
                "cy" to PropSchema.Length,
                "rx" to PropSchema.Length,
                "ry" to PropSchema.Length,
            ),
        ),
        "line" to svgShape(
            linkedMapOf(
                "x1" to PropSchema.Length,
                "y1" to PropSchema.Length,
                "x2" to PropSchema.Length,
                "y2" to PropSchema.Length,
            ),
        ),
        "polyline" to svgShape(
            linkedMapOf("points" to PropSchema.Pattern(NUMBER_LIST_PATTERN, RAFT_UI_MAX_SVG_TEXT)),
        ),
        "polygon" to svgShape(
            linkedMapOf("points" to PropSchema.Pattern(NUMBER_LIST_PATTERN, RAFT_UI_MAX_SVG_TEXT)),
        ),
        "tspan" to svgChild(
            LinkedHashMap<String, PropSchema>().apply {
                putAll(svgTextProps)
                put("fill", PropSchema.Paint)
                put("stroke", PropSchema.Paint)
                put("opacity", svgUnit)
            },
        ),
        "defs" to svgChild(emptyMap()),
        "radialGradient" to svgChild(
            LinkedHashMap<String, PropSchema>().apply {
                put("cx", PropSchema.Length)
                put("cy", PropSchema.Length)
                put("r", PropSchema.Length)
                putAll(svgGradientProps)
            },
        ),
        "clipPath" to svgChild(linkedMapOf("id" to PropSchema.Id)),
        "mask" to svgChild(
            linkedMapOf(
                "id" to PropSchema.Id,
                "x" to PropSchema.Length,
                "y" to PropSchema.Length,
                "width" to PropSchema.Length,
                "height" to PropSchema.Length,
            ),
        ),
        "title" to svgChild(emptyMap()),
    )

    val CATALOG: Catalog = Catalog(
        catalogId = RAFT_UI_CATALOG_ID,
        catalogVersion = RAFT_UI_CATALOG_VERSION,
        components = LinkedHashMap<String, ComponentSpec>().apply {
            putAll(COMPONENTS)
            putAll(SVG_EXTRAS)
        },
        declaredCatalogId = RAFT_UI_CATALOG_ID,
        declaredCatalogVersion = RAFT_UI_CATALOG_VERSION,
    )
}

val RAFT_UI_COMPONENTS: Map<String, ComponentSpec> = RaftUiCatalog.COMPONENTS
val RAFT_UI_SVG_EXTRAS: Map<String, ComponentSpec> = RaftUiCatalog.SVG_EXTRAS
val RAFT_UI_CATALOG: Catalog = RaftUiCatalog.CATALOG
