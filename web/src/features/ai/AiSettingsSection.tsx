/**
 * Port of ui/ai/AiSettingsSection.kt with the A§8.4 layout for the browser (web-spec §14.6):
 * headline, provider list with status words, the selected provider's key panel, models, photos,
 * Test connection. The key-panel copy (§14.7) must stay literally true: change the code and the
 * words together. Crema appears only on the sheet's Save and test, the sheet's one primary action.
 */
import { useDeferredValue, useEffect, useRef, useState } from "react";
import { CoffeeSheet, EyebrowLabel, FieldWell, HairLine, PrimaryButton, QuietButton, SecondaryButton, SoftCard, StatusChip, StatusLine, ToggleRow } from "@/design/components";
import { epochDayOf, timeLabel } from "@/domain/core/format";
import type { Status } from "@/domain/core/status";
import { defaultModel, usd, type AiError, type AiModel, type AiProvider } from "@/domain/ai";
import { clientFor } from "./client";
import { CancelledError } from "./client/transport";
import type { RemoteModel } from "./client/types";
import { ElapsedLine } from "./AnalysisCard";
import { aiActions, currentSettings, endpointOf, readKeyFor, useAiSettings, type AiSettings } from "./aiSettings";
import { getSnapshot } from "@/data/store";
import { aiPrefs as aiPrefsStore } from "@/data/settings";
import { Glyph } from "./glyphs";
import { keyStore } from "./keyStore";
import { useRadioGroup } from "./radioGroup";
import s from "./ai.module.css";

type TestUi = { kind: "idle" } | { kind: "running"; since: number } | { kind: "ok"; modelLabel: string; seconds: string } | { kind: "failed"; error: AiError };
type ModelsUi = { kind: "idle" } | { kind: "loading" } | { kind: "loaded"; models: RemoteModel[] } | { kind: "failed"; error: AiError };

interface Headline {
  status: Status;
  lead: string;
  rest?: string;
}

function testedLabel(at: number | null): string {
  if (at == null) return "tested";
  const today = epochDayOf(Date.now());
  const day = epochDayOf(at);
  if (day === today) return `tested ${timeLabel(at)}`;
  if (day === today - 1) return "tested yesterday";
  const d = new Date(at);
  return `tested ${d.getDate()} ${["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"][d.getMonth()]}`;
}

function headlineOf(st: AiSettings, test: TestUi): Headline {
  if (test.kind === "running") return { status: "live", lead: "Testing", rest: st.modelLabel };
  if (test.kind === "failed") return { status: "fault", lead: test.error.title };
  if (test.kind === "ok") return { status: "good", lead: "Connected", rest: `${test.modelLabel} answered in ${test.seconds} s` };
  if (!st.provider) return { status: "neutral", lead: "Choose a service", rest: "nothing is sent yet" };
  if (st.provider.isCustom && !st.baseUrl) return { status: "neutral", lead: "Needs an address", rest: "nothing is sent yet" };
  // The saved key belongs to another server, so it stays unsent until one is pasted here.
  if (st.keyForOtherHost) return { status: "attention", lead: `Key saved for ${st.keyHost}`, rest: `paste a key for ${st.host}` };
  if (!st.hasKey && !st.keyOptional) return { status: "neutral", lead: "Needs an API key", rest: "nothing is sent yet" };
  if (st.provider.browserCors === "no" && !st.testedOk) return { status: "attention", lead: "Needs setup", rest: "then test the connection" };
  if (st.testedOk) return { status: "good", lead: "Ready", rest: `${st.modelLabel} via ${st.providerName} · ${testedLabel(st.testOkAt)}` };
  if (st.needsTest) return { status: "attention", lead: st.hasKey ? "Key saved" : "Set up", rest: "test the connection before the first analysis" };
  return { status: "neutral", lead: "Key saved", rest: `${st.modelLabel} via ${st.providerName} · not tested yet` };
}

/** "Key saved" Good, "Key for another address" Attention, "Needs setup" Attention, "No key" Neutral. */
function providerStatus(p: AiProvider, st: AiSettings): { status: Status; word: string } {
  const badge = keyStore.badge(p.id);
  const host = p.id === st.providerId ? st.host : p.isCustom ? "" : hostOfUrl(p.baseUrl);
  if (p.browserCors === "no") return { status: "attention", word: "Needs setup" };
  if (badge && host && badge.host === host) return { status: "good", word: "Key saved" };
  if (badge && host && badge.host !== host) return { status: "attention", word: "Key for another address" };
  return { status: "neutral", word: "No key" };
}

function hostOfUrl(u: string): string {
  try {
    return new URL(u).hostname;
  } catch {
    return "";
  }
}

function priceLabel(m: { price: { inputPerM: number; outputPerM: number } | null }): string | null {
  return m.price ? `${usd(m.price.inputPerM)} in · ${usd(m.price.outputPerM)} out per 1M` : null;
}

/** "17 Nov 2026" from "2026-11-17". */
function dateLabel(iso: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso);
  if (!m) return iso;
  return `${Number(m[3])} ${["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"][Number(m[2]) - 1]} ${m[1]}`;
}

const URL_RE = /https:\/\/[^\s'")]+[^\s'").,;]/g;

/** web-spec §14.1: the steps for a provider whose server blocks other sites by default. */
function SetupSteps({ p }: { p: AiProvider }) {
  const origin = typeof location !== "undefined" ? location.origin : "this site";
  const links = [...new Set([...(p.corsEvidence.match(URL_RE) ?? []), ...(p.notes.match(URL_RE) ?? [])])].slice(0, 3);
  return (
    <div className={s.stack} style={{ gap: 6 }}>
      <StatusChip status="attention" text="Needs setup" />
      <ol className={`${s.steps} ${s.body13}`}>
        {p.id === "ollama" && <li>Start Ollama with OLLAMA_ORIGINS set to {origin}, then restart it.</li>}
        {p.id === "lm-studio" && <li>Turn on Enable CORS in LM Studio's server settings.</li>}
        <li>Your browser may ask to let this site reach devices on your local network. This works in Chrome and Edge on the same computer; Safari may block it.</li>
      </ol>
      {links.map((u) => (
        <a key={u} className={`${s.link} ${s.body13}`} href={u} target="_blank" rel="noopener noreferrer">
          {u.replace(/^https:\/\//, "")}
        </a>
      ))}
    </div>
  );
}

export function AiSettingsSection({ inSheet = false }: { inSheet?: boolean }) {
  const st = useAiSettings();
  const [test, setTest] = useState<TestUi>({ kind: "idle" });
  const [keyDraft, setKeyDraft] = useState("");
  const [remember, setRemember] = useState(false);
  const [keyError, setKeyError] = useState<string | null>(null);
  const [replacing, setReplacing] = useState(false);
  const [baseDraft, setBaseDraft] = useState<string | null>(null);
  const [baseError, setBaseError] = useState<string | null>(null);
  const [customModel, setCustomModel] = useState("");
  const [picker, setPicker] = useState(false);
  const testAbort = useRef<AbortController | null>(null);
  // A typed key never outlives the panel.
  useEffect(() => () => testAbort.current?.abort(), []);

  const p = st.provider;
  const headline = headlineOf(st, test);
  const showKeyField = p != null && (!st.hasKey || replacing);
  const host = st.host || (p?.isCustom ? "" : hostOfUrl(p?.baseUrl ?? ""));

  const select = (id: string) => {
    if (id === st.providerId) return;
    testAbort.current?.abort();
    setTest({ kind: "idle" });
    setKeyDraft("");
    setKeyError(null);
    setReplacing(false);
    setBaseDraft(null);
    setBaseError(null);
    void aiActions.setProvider(id);
  };
  const providerRadio = useRadioGroup(
    st.providers.length,
    st.providers.findIndex((x) => x.id === st.providerId),
    (n) => {
      const next = st.providers[n];
      if (next) select(next.id);
    },
  );

  const runTest = async () => {
    // Fresh prefs, not the rendered copy, so a key saved a moment ago is seen.
    const fresh = currentSettings(await aiPrefsStore.get());
    const ep = endpointOf(fresh);
    if (!fresh.ready || !ep) return;
    const key = readKeyFor(fresh);
    testAbort.current?.abort();
    const c = new AbortController();
    testAbort.current = c;
    setTest({ kind: "running", since: Date.now() });
    try {
      const r = await clientFor(ep.api).testConnection(ep, key, fresh.model, c.signal);
      if (r.ok) {
        await aiActions.markTested(fresh.providerId, fresh.signature, Date.now());
        setTest({ kind: "ok", modelLabel: fresh.modelLabel, seconds: (Math.round(r.value.latencyMs / 100) / 10).toFixed(1) });
      } else setTest({ kind: "failed", error: r.error });
    } catch (e) {
      if (!(e instanceof CancelledError)) setTest({ kind: "idle" });
    }
  };

  const saveAddress = async (): Promise<string | null> => {
    if (baseDraft == null) return st.host || null;
    const c = await aiActions.setBaseUrl(baseDraft);
    if (!c.ok) {
      setBaseError(c.error.title + ". " + c.error.message);
      return null;
    }
    setBaseDraft(null);
    setBaseError(null);
    return c.host;
  };

  const saveKeyAndTest = async () => {
    if (!p) return;
    // An address typed but not yet saved is saved first, or the key would be bound to the old host.
    const forHost = p.isCustom ? await saveAddress() : host;
    if (!forHost) {
      setKeyError("Save the address first.");
      return;
    }
    const r = keyStore.save(p.id, keyDraft, forHost, remember);
    if (!r.ok) {
      setKeyError(r.message);
      return;
    }
    setKeyDraft("");
    setKeyError(null);
    setReplacing(false);
    await runTest();
  };

  const removeKey = () => {
    if (!p) return;
    void aiActions.removeKey(p.id);
    setTest({ kind: "idle" });
  };

  return (
    <SoftCard padding={inSheet ? "4px 0" : undefined}>
      <div className={s.settingsHead}>
        <EyebrowLabel text="AI ANALYSIS" />
        {st.host && <span className={`micro ink-low ${s.ellipsis}`}>{st.host.toUpperCase()}</span>}
      </div>
      <StatusLine status={headline.status} lead={headline.lead} rest={headline.rest} />
      <div className={s.gap16} />
      <HairLine />
      <div className={s.gap16} />

      <EyebrowLabel text="SERVICE" as="h3" />
      <div className={s.gap8} />
      <div className={s.providerList} role="radiogroup" aria-label="AI service">
        {st.providers.map((prov, n) => {
          const ps = providerStatus(prov, st);
          const on = prov.id === st.providerId;
          return (
            <button key={prov.id} type="button" role="radio" aria-checked={on} className={s.providerRow} onClick={() => select(prov.id)} {...providerRadio(n)}>
              <span className={s.radioMark} data-on={on} aria-hidden="true" />
              <span className="t-body-l">{prov.name}</span>
              <StatusChip status={ps.status} text={ps.word} />
            </button>
          );
        })}
      </div>

      {p && (
        <>
          <div className={s.gap12} />
          <div className={s.selected}>
            {p.browserCors === "no" && <SetupSteps p={p} />}
            {p.isCustom && (
              <>
                <FieldWell
                  label="BASE URL"
                  type="url"
                  value={baseDraft ?? st.baseUrl}
                  onChange={(v) => {
                    setBaseDraft(v);
                    setBaseError(null);
                  }}
                  placeholder="https://api.example.com/v1"
                  helper="Any OpenAI-compatible API root. https://, or http://localhost for a model on this computer."
                  error={baseError}
                />
                {baseDraft != null && baseDraft.trim() !== st.baseUrl && (
                  <div>
                    <SecondaryButton text="Save address" onClick={() => void saveAddress()} height={40} />
                  </div>
                )}
                <p className={s.body12}>A saved key is only ever sent to the address it was saved for. A new address needs its own key.</p>
              </>
            )}
            {p.keyUrl && (
              <a className={`${s.link} t-body-m`} href={p.keyUrl} target="_blank" rel="noopener noreferrer">
                {st.keyOptional ? "Get it" : "Get a key"} <Glyph name="external" size={14} />
              </a>
            )}
            {p.keyHint && <p className={s.body13}>{p.keyHint}</p>}

            {showKeyField ? (
              <>
                <FieldWell
                  label="API KEY"
                  type="password"
                  value={keyDraft}
                  onChange={(v) => {
                    setKeyDraft(v);
                    setKeyError(null);
                  }}
                  placeholder={st.keyOptional ? "Optional for a model on this computer" : `Paste your key for ${p.name}`}
                  error={keyError}
                  autoComplete="off"
                />
                <label className={`${s.checkbox} t-body-m`}>
                  <input type="checkbox" checked={remember} onChange={(e) => setRemember(e.currentTarget.checked)} />
                  <span>Remember on this device</span>
                </label>
                <p className={s.body12}>
                  {remember
                    ? `Your key is saved in this browser for this site until you remove it, and sent only to ${host || "the address above"} in the request header. Anyone using this browser profile, and any extension allowed on this site, could read it. Don't tick this on a shared computer.`
                    : `Your key is kept only while this page stays open: closing or reloading the tab forgets it, and nothing is saved on this device. It's sent only to ${host || "the address above"}, in the request header, when you run an analysis, test the connection or list models.`}
                </p>
                <div className={s.buttons}>
                  {inSheet ? (
                    <PrimaryButton text="Save and test" onClick={() => void saveKeyAndTest()} enabled={keyDraft.trim() !== ""} height={48} />
                  ) : (
                    <SecondaryButton text="Save and test" onClick={() => void saveKeyAndTest()} enabled={keyDraft.trim() !== ""} height={44} />
                  )}
                  {replacing && <QuietButton text="Cancel" onClick={() => (setReplacing(false), setKeyDraft(""), setKeyError(null))} />}
                </div>
              </>
            ) : (
              st.hasKey && (
                <div className={s.stack} style={{ gap: 6 }}>
                  <EyebrowLabel text="API KEY" />
                  <div className={s.keySaved}>
                    <span className={s.keyDots}>•••• •••• {st.keyHint}</span>
                    <span className={s.body12}>{st.keyRemembered ? "remembered on this device" : "kept until this tab closes or reloads"}</span>
                  </div>
                  <div className={s.buttons}>
                    <QuietButton text="Replace" onClick={() => setReplacing(true)} />
                    <QuietButton text="Remove key" onClick={removeKey} color="var(--fault)" />
                  </div>
                </div>
              )
            )}
            <p className={s.body12}>Coffee has no server, so your key never comes to us. Keys are never put in backups.</p>
          </div>

          <div className={s.gap16} />
          <EyebrowLabel text="MODEL" as="h3" />
          <div className={s.gap8} />
          <ModelList p={p} st={st} customModel={customModel} setCustomModel={setCustomModel} onBrowse={() => setPicker(true)} />

          {!st.modelInCatalog && st.model && (
            <ToggleRow label="This model can see images" checked={st.visionOverride === true} onChange={(v) => void aiActions.setVisionOverride(p.id, v)} />
          )}
          {st.modelSeesImages && (
            <ToggleRow label="Send photos with analyses" subtitle="Up to 3 photos of the brew you're analysing. Untick any photo to keep it out." checked={st.sendPhotos} onChange={(v) => void aiActions.setSendPhotos(v)} />
          )}

          <div className={s.gap16} />
          <SecondaryButton text="Test connection" onClick={() => void runTest()} enabled={st.ready && test.kind !== "running"} className={s.fullWidth} />
          {test.kind === "failed" && (
            <p className={s.body13} style={{ marginTop: 8, whiteSpace: "pre-line" }} role="alert">
              {test.error.message}
            </p>
          )}
          {test.kind === "running" && (
            <div style={{ marginTop: 8 }}>
              <ElapsedLine since={test.since} prefix={`Waiting for ${st.modelLabel} · `} />
            </div>
          )}
          <div className={s.gap16} />
          <HairLine />
          <p className={s.body12} style={{ marginTop: 12 }}>
            {st.price ? `Prices per 1M tokens as of ${dateLabel(st.pricesCheckedOn)}. ` : "Prices for this model aren't known, so analyses show token counts without a cost. "}
            Tests, analyses and Browse go only to {st.host || "the address you set"}.
          </p>
        </>
      )}
      {picker && p && <ModelPickerSheet st={st} onClose={() => setPicker(false)} />}
    </SoftCard>
  );
}

function ModelList({ p, st, customModel, setCustomModel, onBrowse }: { p: AiProvider; st: AiSettings; customModel: string; setCustomModel: (v: string) => void; onBrowse: () => void }) {
  const choose = (id: string) => void aiActions.setModel(p.id, id);
  const custom = st.model !== "" && !p.models.some((m) => m.id === st.model);
  const def = defaultModel(p);
  // The custom row, when shown, is the last radio and is already checked.
  const count = p.models.length + (custom ? 1 : 0);
  const checked = custom ? p.models.length : p.models.findIndex((m) => m.id === st.model);
  const radio = useRadioGroup(count, checked, (n) => {
    const m = p.models[n];
    if (m) choose(m.id);
  });
  return (
    <div className={s.stack}>
      <div role="radiogroup" aria-label="Model">
        {p.models.map((m: AiModel, n) => (
          <button key={m.id} type="button" role="radio" aria-checked={st.model === m.id} className={s.modelRow} onClick={() => choose(m.id)} {...radio(n)}>
            <span className={s.radioMark} data-on={st.model === m.id} aria-hidden="true" />
            <span>
              <span className={`t-body-l ${s.modelTitle}`}>
                {m.label}
                {m.id === def?.id && <span className={`micro ${s.badge}`}>DEFAULT</span>}
                {m.vision && <StatusChip status="neutral" text="Sees photos" />}
              </span>
              {priceLabel(m) && <span className={s.body13}>{priceLabel(m)}</span>}
              {m.why && <span className={s.body12}>{m.why}</span>}
            </span>
          </button>
        ))}
        {custom && (
          <button type="button" role="radio" aria-checked className={s.modelRow} {...radio(p.models.length)}>
            <span className={s.radioMark} data-on aria-hidden="true" />
            <span>
              <span className="t-body-l">Another model</span>
              <span className={s.body13}>{st.model}</span>
            </span>
          </button>
        )}
      </div>
      {(p.modelsListable || p.isCustom || st.keyOptional) && (
        <div>
          <QuietButton text="Browse all models" onClick={onBrowse} enabled={st.baseUrl !== "" && (st.hasKey || st.keyOptional || p.modelsPublic)} />
        </div>
      )}
      <div className={s.gap8} />
      <FieldWell label="USE ANOTHER MODEL ID" value={customModel} onChange={setCustomModel} placeholder="vendor/model-name" helper="Model ids differ between providers. Browse the list or type one." />
      <div>
        <QuietButton
          text="Use"
          enabled={customModel.trim() !== ""}
          onClick={() => {
            choose(customModel);
            setCustomModel("");
          }}
        />
      </div>
    </div>
  );
}

function ModelPickerSheet({ st, onClose }: { st: AiSettings; onClose: () => void }) {
  const [models, setModels] = useState<ModelsUi>({ kind: "loading" });
  const [query, setQuery] = useState("");
  const q = useDeferredValue(query.trim().toLowerCase());
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    const ep = endpointOf(st);
    if (!ep) return;
    const c = new AbortController();
    const key = readKeyFor(currentSettings(getSnapshot().aiPrefs));
    clientFor(ep.api)
      .listModels(ep, key, c.signal)
      .then((r) => setModels(r.ok ? { kind: "loaded", models: r.value } : { kind: "failed", error: r.error }))
      .catch(() => undefined);
    return () => c.abort();
    // Loaded once per open (and per Try again); the endpoint does not change while it is open.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [attempt]);
  const all = models.kind === "loaded" ? models.models : [];
  const shown = q ? all.filter((m) => m.name.toLowerCase().includes(q) || m.id.toLowerCase().includes(q)) : all;
  return (
    <CoffeeSheet label="All models" onClose={onClose}>
      <h2 className="t-headline-s">All models</h2>
      <p className={s.body13} style={{ margin: "4px 0 12px" }}>
        From {st.host}. Only models that answer chat requests are listed.
      </p>
      <div className={s.search}>
        <FieldWell label="SEARCH" value={query} onChange={setQuery} placeholder="Search models" />
      </div>
      {models.kind === "loading" && <p className={s.body13}>Loading the list…</p>}
      {models.kind === "failed" && (
        <div className={s.stack} style={{ gap: 8 }}>
          <p className={s.title}>{models.error.title}</p>
          <p className={s.body13}>{models.error.message}</p>
          <div>
            <SecondaryButton
              text="Try again"
              onClick={() => {
                setModels({ kind: "loading" });
                setAttempt((a) => a + 1);
              }}
            />
          </div>
        </div>
      )}
      {models.kind === "loaded" && shown.length === 0 && <p className={s.body13}>{all.length === 0 ? "This address lists no chat models." : `No model matches “${query.trim()}”.`}</p>}
      <ul className={s.pickerList}>
        {shown.map((m) => (
          <li key={m.id} className={s.pickerRow}>
            <button
              type="button"
              className={s.modelRow}
              onClick={() => {
                if (st.provider) void aiActions.setModel(st.provider.id, m.id);
                onClose();
              }}
            >
              <span className={s.radioMark} data-on={m.id === st.model} aria-hidden="true" />
              <span>
                <span className="t-body-l">{m.name}</span>
                <span className={s.body12}>
                  {m.id}
                  {m.contextLength ? ` · ${Math.round(m.contextLength / 1000)}k` : ""}
                  {m.price ? ` · ${usd(m.price.inputPerM)} in · ${usd(m.price.outputPerM)} out per 1M (live)` : ""}
                </span>
              </span>
            </button>
          </li>
        ))}
      </ul>
    </CoffeeSheet>
  );
}

/** The settings card in a sheet, opened from any analysis card's Add key or AI settings. */
export function AiSettingsSheet(p: { onClose(): void }) {
  const onClose = () => p.onClose();
  return (
    <CoffeeSheet label="AI analysis" onClose={onClose} full>
      <h2 className="t-headline-s" style={{ marginBottom: 12 }}>
        AI analysis
      </h2>
      <AiSettingsSection inSheet />
      <div style={{ display: "flex", justifyContent: "center", marginTop: 8 }}>
        <QuietButton text="Done" onClick={() => history.state && (history.state as { sheet?: boolean }).sheet ? history.back() : onClose()} />
      </div>
    </CoffeeSheet>
  );
}

