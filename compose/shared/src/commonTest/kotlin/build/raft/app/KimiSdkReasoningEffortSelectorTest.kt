package build.raft.app

import androidx.compose.ui.text.input.TextFieldValue
import build.raft.app.data.AgentProfileUpdateRequest
import build.raft.app.data.CREATE_AGENT_KIMI_SDK_RUNTIME_ID
import build.raft.app.data.CREATE_AGENT_KIMI_SDK_SCHEMA_VERSION
import build.raft.app.data.CREATE_AGENT_FORM_PROTOCOL_VERSION
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.InMemorySlockAuthSessionStore
import build.raft.app.data.RawServerMessagePriority
import build.raft.app.data.RealMemberRepository
import build.raft.app.data.SharedAgentCreateDataSchema
import build.raft.app.data.SharedAgentCreateFieldCopy
import build.raft.app.data.SharedAgentCreateFieldSchema
import build.raft.app.data.SharedAgentCreateFormCapabilities
import build.raft.app.data.SharedAgentCreateFormDefinition
import build.raft.app.data.SharedAgentCreateFormOption
import build.raft.app.data.SharedAgentCreateFormOptionSource
import build.raft.app.data.SharedAgentCreateFormOptionSourceRef
import build.raft.app.data.SharedAgentCreateUiSchema
import build.raft.app.data.SharedCreateAgentExactFormKey
import build.raft.app.data.SharedCreateAgentFormStore
import build.raft.app.data.SharedResolvedAgentCreateForm
import build.raft.app.data.SharedRuntimeFormDefinitionRef
import build.raft.app.data.SlockComputersApi
import build.raft.app.data.SlockComputersApiClient
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.SlockNetworkClient
import build.raft.app.data.buildCreateAgentBody
import build.raft.app.data.buildSharedAgentProfileUpdateBody
import build.raft.app.data.errorPresentation
import build.raft.app.model.BASE_REASONING_EFFORT_IDS
import build.raft.app.model.MemberId
import build.raft.app.model.RUNTIME_PROVIDER_MODE_DEFAULT
import build.raft.app.model.RuntimeConfig
import build.raft.app.model.RuntimeModelConfig
import build.raft.app.model.ServerId
import build.raft.app.model.buildRuntimeConfig
import build.raft.app.model.reconcileRuntimeReasoningEffort
import build.raft.app.model.supportsRuntimeReasoningEffort
import build.raft.app.model.validateRuntimeReasoningEffort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel

/**
 * task #275: kimi-sdk reasoning-effort selector driven only by live server
 * capability (runtime-form protocol, botiverse/slock #7147). Every test maps
 * to a review mutation of the frozen rules:
 *  1. selector visibility gate + no BASE_REASONING_EFFORT_IDS fallback;
 *  2. create outbound ref/effort on the raw wire;
 *  3. edit outbound untouched/explicit/model-switch splits;
 *  4. stale/async fencing seams;
 *  5. 409 server-reason display path with exactly one outbound;
 *  6. static guards keep kimi null against upper-layer leaks.
 */
class KimiSdkReasoningEffortSelectorTest {

    // ── 1. Selector visibility gate ──

    @Test
    fun kimiSelectorCapabilityRequiresLiveEffortsAndValidDefault() {
        val liveModels =
            listOf(
                SharedCreateAgentRuntimeModelOption(
                    id = "k2",
                    label = "Kimi K2",
                    supportedReasoningEfforts = listOf("low", "high"),
                    defaultReasoningEffort = "low"
                )
            )
        assertEquals(
            KimiSdkLiveReasoningCapability(listOf("low", "high"), "low"),
            kimiSdkLiveReasoningCapability(CREATE_AGENT_KIMI_SDK_RUNTIME_ID, "k2", liveModels)
        )
        // Empty live efforts → hidden.
        assertNull(
            kimiSdkLiveReasoningCapability(
                CREATE_AGENT_KIMI_SDK_RUNTIME_ID,
                "k2",
                listOf(SharedCreateAgentRuntimeModelOption(id = "k2", label = "Kimi K2"))
            )
        )
        // Default outside the supported set → hidden.
        assertNull(
            kimiSdkLiveReasoningCapability(
                CREATE_AGENT_KIMI_SDK_RUNTIME_ID,
                "k2",
                listOf(
                    SharedCreateAgentRuntimeModelOption(
                        id = "k2",
                        label = "Kimi K2",
                        supportedReasoningEfforts = listOf("low"),
                        defaultReasoningEffort = "max"
                    )
                )
            )
        )
        // Selected model absent from the live catalog → hidden.
        assertNull(kimiSdkLiveReasoningCapability(CREATE_AGENT_KIMI_SDK_RUNTIME_ID, "k1.5", liveModels))
        // Blank model → hidden.
        assertNull(kimiSdkLiveReasoningCapability(CREATE_AGENT_KIMI_SDK_RUNTIME_ID, " ", liveModels))
        // Non-kimi runtime → never this gate.
        assertNull(kimiSdkLiveReasoningCapability("codex", "k2", liveModels))
    }

    @Test
    fun kimiSelectorNeverFallsBackToStaticBaseEffortIds() {
        // The shared dropdown helper keeps its static fallback for non-kimi
        // runtimes: even for runtime="kimi-sdk" it would yield values...
        val fallbackItems =
            createAgentReasoningDropdownItems(
                runtime = CREATE_AGENT_KIMI_SDK_RUNTIME_ID,
                model = "k2",
                runtimeModelsOverride = emptyList()
            )
        assertTrue(fallbackItems.any { it.id in BASE_REASONING_EFFORT_IDS })
        // ...but the kimi capability gate ignores it: an empty live catalog
        // means the selector stays hidden instead of showing static values.
        assertNull(kimiSdkLiveReasoningCapability(CREATE_AGENT_KIMI_SDK_RUNTIME_ID, "k2", emptyList()))
        val items =
            createAgentKimiSdkReasoningDropdownItems(
                capability = KimiSdkLiveReasoningCapability(listOf("low", "high"), "low")
            )
        assertEquals(listOf(CREATE_AGENT_DEFAULT_REASONING_ID, "low", "high"), items.map { it.id })
        // Unknown live ids render with their raw id as label (no static table lookup).
        val withUnknown =
            createAgentKimiSdkReasoningDropdownItems(
                capability = KimiSdkLiveReasoningCapability(listOf("low", "beam"), "low")
            )
        assertEquals(listOf(CREATE_AGENT_DEFAULT_REASONING_ID, "low", "beam"), withUnknown.map { it.id })
        assertEquals("beam", withUnknown.last().label)
    }

    // ── 2. Create outbound on the raw wire ──

    @Test
    fun kimiCreateOutboundAttachesRefAndExplicitEffort() {
        val body = kimiCreateBody(chosenEffort = "high", capabilityActive = true)
        assertTrue(
            """"formDefinitionRef":{"protocolVersion":1,"runtimeId":"kimi-sdk","schemaVersion":"kimi-sdk.create.v1"}""" in body,
            "raw create body must carry the exact kimi-sdk form ref: $body"
        )
        assertTrue(""""reasoningEffort":"high"""" in body, "explicit effort must be on the raw body: $body")
    }

    @Test
    fun kimiCreateOutboundDefaultOmitsEffortButKeepsRef() {
        val body = kimiCreateBody(chosenEffort = null, capabilityActive = true)
        assertTrue(
            """"formDefinitionRef":{"protocolVersion":1,"runtimeId":"kimi-sdk","schemaVersion":"kimi-sdk.create.v1"}""" in body,
            "Default selection still carries the ref: $body"
        )
        assertFalse("reasoningEffort" in body, "Default must omit reasoningEffort entirely (explicitNulls=false): $body")
    }

    @Test
    fun kimiCreateOutboundWithoutCapabilityHasNoRefAndNoEffort() {
        // No resolved form → capability absent; a stale chosen effort must be stripped.
        val noForm = kimiCreateBody(chosenEffort = "high", capabilityActive = false)
        assertFalse("formDefinitionRef" in noForm, "no ref without live capability: $noForm")
        assertFalse("reasoningEffort" in noForm, "no effort key at all without capability: $noForm")
        // Empty live efforts → same fail-closed strip even with the form resolved.
        val emptyEfforts =
            kimiCreateBody(
                chosenEffort = "high",
                capabilityActive = true,
                liveModels = listOf(SharedCreateAgentRuntimeModelOption(id = "k2", label = "Kimi K2"))
            )
        assertFalse("formDefinitionRef" in emptyEfforts, "empty live efforts must drop the ref: $emptyEfforts")
        assertFalse("reasoningEffort" in emptyEfforts, "empty live efforts must strip the effort: $emptyEfforts")
    }

    // ── 3. Edit outbound splits ──

    @Test
    fun kimiEditUntouchedSendsNullEffortAndNoRef() {
        val decision =
            membersKimiSdkReasoningSaveDecision(
                capability = kimiEditCapability(),
                formRef = kimiSdkRef,
                effortTouched = false,
                modelChanged = false,
                currentEffort = "high"
            )
        assertNull(decision.reasoningEffort)
        assertNull(decision.formDefinitionRef)
        val body = kimiEditBody(decision)
        assertTrue(""""reasoningEffort":null""" in body, "untouched edit keeps explicit null effort: $body")
        assertFalse("formDefinitionRef" in body, "untouched edit must not carry a ref: $body")
    }

    @Test
    fun kimiEditExplicitChangeSendsEffortAndRef() {
        val decision =
            membersKimiSdkReasoningSaveDecision(
                capability = kimiEditCapability(),
                formRef = kimiSdkRef,
                effortTouched = true,
                modelChanged = false,
                currentEffort = "high"
            )
        assertEquals("high", decision.reasoningEffort)
        assertEquals(kimiSdkRef, decision.formDefinitionRef)
        val body = kimiEditBody(decision)
        assertTrue(""""reasoningEffort":"high"""" in body, body)
        assertTrue(
            """"formDefinitionRef":{"protocolVersion":1,"runtimeId":"kimi-sdk","schemaVersion":"kimi-sdk.create.v1"}""" in body,
            body
        )
        // Explicit Default pick: null effort but still schema-backed via the ref.
        val defaultDecision =
            membersKimiSdkReasoningSaveDecision(
                capability = kimiEditCapability(),
                formRef = kimiSdkRef,
                effortTouched = true,
                modelChanged = false,
                currentEffort = null
            )
        assertNull(defaultDecision.reasoningEffort)
        assertEquals(kimiSdkRef, defaultDecision.formDefinitionRef)
        val defaultBody = kimiEditBody(defaultDecision)
        assertTrue(""""reasoningEffort":null""" in defaultBody, defaultBody)
        assertTrue("formDefinitionRef" in defaultBody, defaultBody)
    }

    @Test
    fun kimiEditModelSwitchReconcilesAgainstNewModelAndNeverLeaks() {
        // New model also supports "high" → kept, ref attached.
        val kept =
            membersKimiSdkReasoningSaveDecision(
                capability = kimiEditCapability(),
                formRef = kimiSdkRef,
                effortTouched = false,
                modelChanged = true,
                currentEffort = "high"
            )
        assertEquals("high", kept.reasoningEffort)
        assertEquals(kimiSdkRef, kept.formDefinitionRef)
        // New model does NOT support the old effort → dropped to null, old value never leaks.
        val dropped =
            membersKimiSdkReasoningSaveDecision(
                capability = KimiSdkLiveReasoningCapability(listOf("low"), "low"),
                formRef = kimiSdkRef,
                effortTouched = false,
                modelChanged = true,
                currentEffort = "ultra"
            )
        assertNull(dropped.reasoningEffort)
        assertEquals(kimiSdkRef, dropped.formDefinitionRef)
        val droppedBody = kimiEditBody(dropped)
        assertFalse("ultra" in droppedBody, "old model effort must never leak: $droppedBody")
        // Model switch without live capability → fail closed, no ref.
        val noCapability =
            membersKimiSdkReasoningSaveDecision(
                capability = null,
                formRef = null,
                effortTouched = false,
                modelChanged = true,
                currentEffort = "high"
            )
        assertNull(noCapability.reasoningEffort)
        assertNull(noCapability.formDefinitionRef)
        // Capability lost after an explicit pick → fail closed too.
        val lostCapability =
            membersKimiSdkReasoningSaveDecision(
                capability = null,
                formRef = null,
                effortTouched = true,
                modelChanged = false,
                currentEffort = "high"
            )
        assertNull(lostCapability.reasoningEffort)
        assertNull(lostCapability.formDefinitionRef)
        // A wrong-runtime ref never rides the kimi decision.
        val wrongRef =
            membersKimiSdkReasoningSaveDecision(
                capability = kimiEditCapability(),
                formRef = SharedRuntimeFormDefinitionRef(1, "builtin", "builtin-pi.create.v3"),
                effortTouched = true,
                modelChanged = false,
                currentEffort = "high"
            )
        assertNull(wrongRef.reasoningEffort)
        assertNull(wrongRef.formDefinitionRef)
    }

    @Test
    fun kimiEditRefOnlyEmittedWithRuntimeConfig() {
        val bodyWithoutRuntimeConfig =
            buildSharedAgentProfileUpdateBody(
                AgentProfileUpdateRequest(
                    agentId = MemberId("agent-1"),
                    displayName = "Agent One",
                    description = null,
                    formDefinitionRef = kimiSdkRef
                )
            )
        assertFalse("formDefinitionRef" in bodyWithoutRuntimeConfig, bodyWithoutRuntimeConfig)
        val bodyWithNullConfig =
            buildSharedAgentProfileUpdateBody(
                AgentProfileUpdateRequest(
                    agentId = MemberId("agent-1"),
                    displayName = "Agent One",
                    description = null,
                    runtimeConfig = null,
                    includeRuntimeConfig = true,
                    formDefinitionRef = kimiSdkRef
                )
            )
        assertFalse("formDefinitionRef" in bodyWithNullConfig, bodyWithNullConfig)
    }

    // ── 4. Stale/async safety seams ──

    @Test
    fun kimiSdkFormLoadsThroughSharedRuntimeFormChain() = runPortableCoroutineTest {
        val api = KimiFormComputersApi(deferDefinitions = true)
        val store = SharedCreateAgentFormStore(SlockComputersApiClient(api), this)
        val load = async { store.loadSupportedKimiSdkForm("server-1", "machine-1") }
        api.definitionResponses.receive().complete(ok(kimiSdkDefinitionBody))
        val form = assertIs<DataResult.Content<SharedResolvedAgentCreateForm>>(load.await()).data
        assertEquals(kimiSdkRef, form.definition.ref)
        assertEquals(setOf("model", "reasoningEffort", "envVars"), form.definition.dataSchema.properties.keys)
        assertEquals(setOf("model"), form.definition.dataSchema.required)
        assertEquals(1, api.definitionCallCount)
        assertEquals(listOf("model"), api.sourceCalls)
    }

    @Test
    fun kimiSdkFormFailsClosedWithoutSelectableRef() = runPortableCoroutineTest {
        val missingRefApi =
            KimiFormComputersApi(
                runtimeOptionsBody =
                    kimiSdkRuntimeOptionsBody.replace(
                        ""","formDefinitionRef":{"protocolVersion":1,"runtimeId":"kimi-sdk","schemaVersion":"kimi-sdk.create.v1"}""",
                        ""
                    )
            )
        val missingRefStore = SharedCreateAgentFormStore(SlockComputersApiClient(missingRefApi), this)
        assertEquals(
            "runtime_form_unavailable_form_ref_missing",
            assertIs<DataResult.Error>(missingRefStore.loadSupportedKimiSdkForm("server-1", "machine-1")).error.code
        )
        assertEquals(0, missingRefApi.definitionCallCount)

        val notSelectableApi =
            KimiFormComputersApi(
                runtimeOptionsBody =
                    kimiSdkRuntimeOptionsBody
                        .replace("\"availableForNew\":true", "\"availableForNew\":false")
                        .replace("\"canSelectInThisContext\":true", "\"canSelectInThisContext\":false")
            )
        val notSelectableStore = SharedCreateAgentFormStore(SlockComputersApiClient(notSelectableApi), this)
        assertEquals(
            "runtime_form_unavailable_runtime_not_selectable",
            assertIs<DataResult.Error>(notSelectableStore.loadSupportedKimiSdkForm("server-1", "machine-1")).error.code
        )
        assertEquals(0, notSelectableApi.definitionCallCount)
    }

    @Test
    fun kimiSdkEditLoaderAcceptsCurrentManageableRuntime() = runPortableCoroutineTest {
        val currentManageableBody =
            kimiSdkRuntimeOptionsBody
                .replace("\"current\":false", "\"current\":true")
                .replace("\"availableForNew\":true", "\"availableForNew\":false")
                .replace("\"canSelectInThisContext\":true", "\"canSelectInThisContext\":false")
        val api = KimiFormComputersApi(runtimeOptionsBody = currentManageableBody, deferDefinitions = true)
        val store = SharedCreateAgentFormStore(SlockComputersApiClient(api), this)

        assertEquals(
            "runtime_form_unavailable_runtime_not_selectable",
            assertIs<DataResult.Error>(store.loadSupportedKimiSdkForm("server-1", "machine-1")).error.code
        )

        val editLoad = async { store.loadSupportedKimiSdkFormForEdit("server-1", "machine-1") }
        api.definitionResponses.receive().complete(ok(kimiSdkDefinitionBody))
        assertIs<DataResult.Content<SharedResolvedAgentCreateForm>>(editLoad.await())
        assertEquals(1, api.definitionCallCount)
    }

    @Test
    fun kimiSdkStaleForcedCompletionCannotReplaceNewerFormGeneration() = runPortableCoroutineTest {
        val api = KimiFormComputersApi(deferDefinitions = true)
        val store = SharedCreateAgentFormStore(SlockComputersApiClient(api), this)
        val older = async { store.loadExactForm("server-1", "machine-1", kimiSdkRef, force = true) }
        val olderResponse = api.definitionResponses.receive()
        val newer = async { store.loadExactForm("server-1", "machine-1", kimiSdkRef, force = true) }
        val newerResponse = api.definitionResponses.receive()

        newerResponse.complete(ok(kimiSdkDefinitionBody.replace("\"label\":\"Model\"", "\"label\":\"Model newer\"")))
        assertIs<DataResult.Content<SharedResolvedAgentCreateForm>>(newer.await())
        olderResponse.complete(ok(kimiSdkDefinitionBody.replace("\"label\":\"Model\"", "\"label\":\"Model stale\"")))
        assertIs<DataResult.Content<SharedResolvedAgentCreateForm>>(older.await())

        val key = SharedCreateAgentExactFormKey("server-1", "machine-1", kimiSdkRef)
        val published = requireNotNull(store.state.value.resolvedForms[key])
        assertEquals("Model newer", published.definition.uiSchema.localization.getValue("model").label)
    }

    @Test
    fun kimiSdkFormChainRejectsUnsupportedRefsAndDriftedDefinitions() = runPortableCoroutineTest {
        val api = KimiFormComputersApi()
        val store = SharedCreateAgentFormStore(SlockComputersApiClient(api), this)
        listOf(
            SharedRuntimeFormDefinitionRef(1, "kimi-sdk", "kimi-sdk.create.v2"),
            SharedRuntimeFormDefinitionRef(2, "kimi-sdk", "kimi-sdk.create.v1"),
            SharedRuntimeFormDefinitionRef(1, "builtin", "builtin-pi.create.v4")
        ).forEach { ref ->
            assertEquals(
                "runtime_form_ref_unsupported",
                assertIs<DataResult.Error>(store.loadExactForm("server-1", "machine-1", ref)).error.code
            )
        }
        assertEquals(0, api.definitionCallCount)

        // A definition whose identity drifts from the requested ref is rejected.
        val driftedApi = KimiFormComputersApi(
            definitionBody = kimiSdkDefinitionBody.replace("kimi-sdk.create.v1", "kimi-sdk.create.v2")
        )
        val driftedClient = SlockComputersApiClient(driftedApi)
        assertEquals(
            "runtime_form_definition_contract_mismatch",
            assertIs<DataResult.Error>(driftedClient.runtimeFormDefinition(ServerId("server-1"), "machine-1", kimiSdkRef)).error.code
        )
        // The kimi shape is pinned: dropping the reasoningEffort field or adding
        // unknown topology fails closed.
        listOf(
            kimiSdkDefinitionBody.replace(
                """"reasoningEffort":{"type":"string","title":"Thinking effort"},"""",
                ""
            ),
            kimiSdkDefinitionBody.replace(
                "\"properties\":{",
                "\"unknownTopology\":true,\"properties\":{"
            )
        ).forEach { body ->
            val result =
                SlockComputersApiClient(KimiFormComputersApi(definitionBody = body))
                    .runtimeFormDefinition(ServerId("server-1"), "machine-1", kimiSdkRef)
            assertEquals("runtime_form_definition_contract_mismatch", assertIs<DataResult.Error>(result).error.code)
        }
    }

    @Test
    fun membersProfileEditAuthorityMatchSeamBlocksStaleSaves() {
        val opened =
            MembersAgentProfileEditAuthority(
                memberId = MemberId("agent-1"),
                environmentId = "https://api.example.test",
                accountId = "account-1",
                serverId = ServerId("server-1"),
                profileGeneration = 7
            )
        assertTrue(
            membersAgentProfileEditAuthorityMatches(
                opened = opened,
                current = opened,
                openedCatalogGeneration = 3,
                currentCatalogGeneration = 3
            )
        )
        // Catalog generation moved after open → save blocked.
        assertFalse(
            membersAgentProfileEditAuthorityMatches(
                opened = opened,
                current = opened,
                openedCatalogGeneration = 3,
                currentCatalogGeneration = 4
            )
        )
        // Profile reloaded after open → save blocked.
        assertFalse(
            membersAgentProfileEditAuthorityMatches(
                opened = opened,
                current = opened.copy(profileGeneration = 8),
                openedCatalogGeneration = 3,
                currentCatalogGeneration = 3
            )
        )
    }

    // ── 5. 409 server-reason path ──

    @Test
    fun kimiEdit409SurfacesServerReasonWithExactlyOneOutbound() = runPortableCoroutineTest {
        val reason = "Update Raft on this device, then reselect the Kimi model and reasoning setting"
        val client =
            RecordingKimiMemberNetworkClient(
                patchResponse =
                    HomeNetworkResponse(
                        code = 409,
                        bodyText = null,
                        errorBodyText = """{"error":"$reason"}"""
                    )
            )
        val repository = RealMemberRepository(sampleKimiSessionStore(), client)

        val result =
            repository.updateAgentProfile(
                DataRequestPriority.Visible,
                AgentProfileUpdateRequest(
                    agentId = MemberId("agent-1"),
                    displayName = "Agent One",
                    description = null,
                    runtimeConfig =
                        RuntimeConfig(
                            runtime = CREATE_AGENT_KIMI_SDK_RUNTIME_ID,
                            model = RuntimeModelConfig.Preset("k2.5")
                        ),
                    includeRuntimeConfig = true
                )
            )

        val error = assertIs<DataResult.Error>(result).error
        assertEquals("http_409", error.code)
        assertEquals(reason, error.serverMessage)
        val presentation = error.errorPresentation()
        assertEquals(RawServerMessagePriority.ServerMessageFirst, presentation.rawServerMessagePriority)
        assertEquals(reason, presentation.rawServerMessage)
        // No optimistic rewrite, no retry: exactly one outbound PATCH, legacy
        // no-ref shape with explicit null effort (retention semantics).
        assertEquals(1, client.patchBodies.size)
        val outbound = requireNotNull(client.patchBodies.single())
        assertTrue(""""reasoningEffort":null""" in outbound, outbound)
        assertFalse("formDefinitionRef" in outbound, outbound)
    }

    @Test
    fun kimiEditSuccessCarriesRefAndEffortThroughRepository() = runPortableCoroutineTest {
        val client =
            RecordingKimiMemberNetworkClient(
                patchResponse = HomeNetworkResponse(code = 200, bodyText = """{"id":"agent-1","name":"agent-one"}""")
            )
        val repository = RealMemberRepository(sampleKimiSessionStore(), client)
        val decision =
            membersKimiSdkReasoningSaveDecision(
                capability = kimiEditCapability(),
                formRef = kimiSdkRef,
                effortTouched = true,
                modelChanged = false,
                currentEffort = "high"
            )

        val result =
            repository.updateAgentProfile(
                DataRequestPriority.Visible,
                AgentProfileUpdateRequest(
                    agentId = MemberId("agent-1"),
                    displayName = "Agent One",
                    description = null,
                    runtimeConfig =
                        RuntimeConfig(
                            runtime = CREATE_AGENT_KIMI_SDK_RUNTIME_ID,
                            model = RuntimeModelConfig.Preset("k2"),
                            reasoningEffort = decision.reasoningEffort
                        ),
                    includeRuntimeConfig = true,
                    formDefinitionRef = decision.formDefinitionRef
                )
            )

        assertIs<DataResult.Content<*>>(result)
        val body = requireNotNull(client.patchBodies.single())
        assertTrue(""""reasoningEffort":"high"""" in body, body)
        assertTrue(
            """"formDefinitionRef":{"protocolVersion":1,"runtimeId":"kimi-sdk","schemaVersion":"kimi-sdk.create.v1"}""" in body,
            body
        )
    }

    // ── 6. Static guards never leak kimi efforts ──

    @Test
    fun staticReasoningGuardsKeepKimiSdkNull() {
        assertFalse(supportsRuntimeReasoningEffort(CREATE_AGENT_KIMI_SDK_RUNTIME_ID))
        assertNull(validateRuntimeReasoningEffort(CREATE_AGENT_KIMI_SDK_RUNTIME_ID, "k2", "high"))
        assertNull(
            validateRuntimeReasoningEffort(
                runtime = CREATE_AGENT_KIMI_SDK_RUNTIME_ID,
                model = "k2",
                current = "high",
                supportedReasoningEfforts = listOf("low", "high"),
                defaultReasoningEffort = "high"
            )
        )
        assertNull(
            reconcileRuntimeReasoningEffort(
                runtime = CREATE_AGENT_KIMI_SDK_RUNTIME_ID,
                model = "k2",
                current = "high",
                supportedReasoningEfforts = listOf("low", "high"),
                defaultReasoningEffort = "high"
            )
        )
        val config =
            buildRuntimeConfig(
                runtime = CREATE_AGENT_KIMI_SDK_RUNTIME_ID,
                model = "k2",
                customModelMode = false,
                reasoningEffort = "high",
                reasoningSupportedEfforts = listOf("low", "high"),
                reasoningDefaultEffort = "low"
            )
        assertNull(config.reasoningEffort)
    }

    // ── Fixtures ──

    private fun kimiEditCapability(): KimiSdkLiveReasoningCapability =
        KimiSdkLiveReasoningCapability(supportedReasoningEfforts = listOf("low", "high"), defaultReasoningEffort = "low")

    private fun kimiCreateBody(
        chosenEffort: String?,
        capabilityActive: Boolean,
        liveModels: List<SharedCreateAgentRuntimeModelOption> =
            listOf(
                SharedCreateAgentRuntimeModelOption(
                    id = "k2",
                    label = "Kimi K2",
                    supportedReasoningEfforts = listOf("low", "high"),
                    defaultReasoningEffort = "low"
                )
            )
    ): String {
        val submit =
            buildSharedCreateAgentDialogSubmit(
                name = "kimi-bot",
                description = "",
                selectedMachineId = "machine-1",
                requiredComputer = null,
                external = false,
                runtime = CREATE_AGENT_KIMI_SDK_RUNTIME_ID,
                model = "k2",
                customModelMode = false,
                customModelName = "",
                providerMode = RUNTIME_PROVIDER_MODE_DEFAULT,
                providerApiUrl = "",
                providerApiKey = TextFieldValue(""),
                fastMode = false,
                reasoningEffort = chosenEffort,
                envVarEntries = emptyList(),
                command = "",
                runtimeModelsOverride = liveModels,
                kimiSdkForm = if (capabilityActive) kimiSdkFormFixture() else null
            )
        assertEquals(CREATE_AGENT_KIMI_SDK_RUNTIME_ID, submit.runtimeConfig?.runtime)
        // Mirror SharedCreateAgentCoordinator.createAgent's body mapping so the
        // assertion targets the raw outbound JSON, not response hydration.
        val request = submit.toCreateAgentRequest()
        return buildCreateAgentBody(
            build.raft.app.data.CreateAgentBody(
                name = request.name,
                description = request.description,
                model = (request.runtimeConfig?.model as? RuntimeModelConfig.Preset)?.id,
                runtime = request.runtimeConfig?.runtime,
                reasoningEffort = request.runtimeConfig?.reasoningEffort,
                machineId = request.machineId,
                envVars = request.runtimeConfig?.envVars,
                runtimeConfig = request.runtimeConfig,
                formDefinitionRef = request.formDefinitionRef,
                external = request.external.takeIf { it }
            )
        )
    }

    private fun kimiEditBody(decision: MembersKimiSdkReasoningSaveDecision): String =
        buildSharedAgentProfileUpdateBody(
            AgentProfileUpdateRequest(
                agentId = MemberId("agent-1"),
                displayName = "Agent One",
                description = null,
                runtimeConfig =
                    RuntimeConfig(
                        runtime = CREATE_AGENT_KIMI_SDK_RUNTIME_ID,
                        model = RuntimeModelConfig.Preset("k2"),
                        reasoningEffort = decision.reasoningEffort
                    ),
                includeRuntimeConfig = true,
                formDefinitionRef = decision.formDefinitionRef
            )
        )

    private fun kimiSdkFormFixture(): SharedResolvedAgentCreateForm {
        val properties =
            mapOf(
                "model" to SharedAgentCreateFieldSchema.StringField("Model", 1, null, false),
                "reasoningEffort" to SharedAgentCreateFieldSchema.StringField("Thinking effort", null, null, false),
                "envVars" to SharedAgentCreateFieldSchema.StringMapField("Environment Variables")
            )
        val definition =
            SharedAgentCreateFormDefinition(
                ref = kimiSdkRef,
                dataSchema = SharedAgentCreateDataSchema(required = setOf("model"), properties = properties),
                uiSchema =
                    SharedAgentCreateUiSchema(
                        order = listOf("model", "reasoningEffort", "envVars"),
                        advancedPointers = setOf("/envVars"),
                        visibility = emptyList(),
                        localization =
                            properties.mapValues { (name, _) ->
                                SharedAgentCreateFieldCopy(label = name, hint = null, placeholder = null)
                            }
                    ),
                capabilities =
                    SharedAgentCreateFormCapabilities(
                        providerKinds = emptySet(),
                        writeOnlyPointers = emptySet(),
                        forbiddenPointers = setOf("/hostUserState")
                    ),
                optionSources =
                    mapOf(
                        "model" to SharedAgentCreateFormOptionSourceRef.Select(kimiSdkRef, "model", "/model")
                    )
            )
        val modelSource =
            SharedAgentCreateFormOptionSource.Select(
                ref = kimiSdkRef,
                sourceId = "model",
                pointer = "/model",
                options =
                    listOf(
                        SharedAgentCreateFormOption("k2", "Kimi K2", null),
                        SharedAgentCreateFormOption("k2.5", "Kimi K2.5", null)
                    ),
                defaultValue = "k2"
            )
        return SharedResolvedAgentCreateForm(definition, mapOf("model" to modelSource))
    }

    private class KimiFormComputersApi(
        var runtimeOptionsBody: String = kimiSdkRuntimeOptionsBody,
        var definitionBody: String = kimiSdkDefinitionBody,
        var modelSourceBody: String = kimiSdkModelSourceBody,
        private val deferDefinitions: Boolean = false
    ) : SlockComputersApi {
        val definitionResponses = Channel<CompletableDeferred<HomeNetworkResponse>>(Channel.UNLIMITED)
        var runtimeOptionsCallCount = 0
        var definitionCallCount = 0
        val sourceCalls = mutableListOf<String>()

        override suspend fun runtimeOptions(serverId: ServerId, machineId: String): HomeNetworkResponse {
            runtimeOptionsCallCount += 1
            return ok(runtimeOptionsBody)
        }

        override suspend fun runtimeFormDefinition(
            serverId: ServerId,
            machineId: String,
            ref: SharedRuntimeFormDefinitionRef
        ): HomeNetworkResponse {
            definitionCallCount += 1
            if (!deferDefinitions) return ok(definitionBody)
            return CompletableDeferred<HomeNetworkResponse>().also { definitionResponses.send(it) }.await()
        }

        override suspend fun runtimeFormOptionSource(
            serverId: ServerId,
            machineId: String,
            ref: SharedRuntimeFormDefinitionRef,
            sourceId: String
        ): HomeNetworkResponse {
            sourceCalls += sourceId
            return ok(modelSourceBody)
        }

        override suspend fun machines(serverId: ServerId): HomeNetworkResponse = error("unused")
        override suspend fun registerMachine(serverId: ServerId, body: String): HomeNetworkResponse = error("unused")
        override suspend fun renameMachine(serverId: ServerId, machineId: String, body: String): HomeNetworkResponse = error("unused")
        override suspend fun deleteMachine(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun rotateMachineKey(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun machineWorkspaces(serverId: ServerId, machineId: String): HomeNetworkResponse = error("unused")
        override suspend fun runtimeModels(serverId: ServerId, machineId: String, runtime: String): HomeNetworkResponse = error("unused")
        override suspend fun deleteMachineWorkspace(serverId: ServerId, machineId: String, directoryName: String): HomeNetworkResponse = error("unused")
    }

    private class RecordingKimiMemberNetworkClient(
        var patchResponse: HomeNetworkResponse
    ) : SlockNetworkClient {
        val patchBodies = mutableListOf<String?>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?
        ): HomeNetworkResponse {
            if (path.trim('/') == "agents/agent-1" && method == "PATCH") {
                patchBodies += bodyText
                return patchResponse
            }
            error("Unexpected request $method $path")
        }
    }

    private fun sampleKimiSessionStore(): InMemorySlockAuthSessionStore =
        InMemorySlockAuthSessionStore(
            StoredAuthSession(
                user =
                    StoredAuthUser(
                        id = "user-1",
                        email = "user@slock.ai",
                        name = "User",
                        displayName = "Slock User",
                        description = null,
                        avatarUrl = null,
                        emailVerified = true
                    ),
                accessToken = "sample-access",
                refreshToken = "sample-refresh",
                selectedServerId = "server-1",
                selectedServerSlug = "slock-android",
                servers =
                    listOf(
                        StoredAuthServer(
                            id = "server-1",
                            name = "Slock Android",
                            slug = "slock-android",
                            ownerId = "user-1",
                            onboardingAgentId = "agent-1",
                            plan = "team",
                            planDowngradedAt = null,
                            role = "owner",
                            createdAt = "2026-05-06T00:00:00Z"
                        )
                    )
            )
        )

    private companion object {
        val kimiSdkRef =
            SharedRuntimeFormDefinitionRef(
                CREATE_AGENT_FORM_PROTOCOL_VERSION,
                CREATE_AGENT_KIMI_SDK_RUNTIME_ID,
                CREATE_AGENT_KIMI_SDK_SCHEMA_VERSION
            )

        // Mirrors the server runtime-options catalog with a selectable kimi-sdk option.
        val kimiSdkRuntimeOptionsBody =
            """{"context":"new_agent","machineId":"machine-1","options":[{"runtimeId":"kimi-sdk","capabilityStatus":"available","admissionStatus":"available_for_new","admissionReason":null,"current":false,"availableForNew":true,"manageableForCurrentAgent":true,"canSelectInThisContext":true,"formDefinitionRef":{"protocolVersion":1,"runtimeId":"kimi-sdk","schemaVersion":"kimi-sdk.create.v1"}}]}"""

        // kimi-sdk.create.v1: model (required), reasoningEffort (optional), envVars.
        val kimiSdkDefinitionBody =
            """{"protocolVersion":1,"runtimeId":"kimi-sdk","schemaVersion":"kimi-sdk.create.v1","dataSchema":{"type":"object","additionalProperties":false,"required":["model"],"properties":{"model":{"type":"string","title":"Model","minLength":1},"reasoningEffort":{"type":"string","title":"Thinking effort"},"envVars":{"type":"object","title":"Environment Variables","additionalProperties":{"type":"string"}}}},"uiSchema":{"order":["model","reasoningEffort","envVars"],"layout":{"advanced":["/envVars"]},"visibility":[],"localization":{"model":{"label":"Model"},"reasoningEffort":{"label":"Thinking effort"},"envVars":{"label":"Environment Variables"}}},"capabilities":{"providerKinds":[],"writeOnlyPointers":[],"forbiddenPointers":["/hostUserState"]},"optionSources":{"model":{"protocolVersion":1,"runtimeId":"kimi-sdk","schemaVersion":"kimi-sdk.create.v1","sourceId":"model","kind":"select","pointer":"/model"}}}"""

        // Per-model entries carry reasoning metadata on the wire; strict decoding
        // must tolerate them while the domain keeps the live catalog as authority.
        val kimiSdkModelSourceBody =
            """{"protocolVersion":1,"runtimeId":"kimi-sdk","schemaVersion":"kimi-sdk.create.v1","sourceId":"model","kind":"select","pointer":"/model","options":[{"value":"k2","label":"Kimi K2","supportedReasoningEfforts":["low","high"],"defaultReasoningEffort":"low"},{"value":"k2.5","label":"Kimi K2.5"}],"defaultValue":"k2"}"""

        fun ok(body: String) = HomeNetworkResponse(code = 200, bodyText = body)
    }
}
