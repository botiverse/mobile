import connection from '@ohos.net.connection';
import http from '@ohos.net.http';

/**
 * Task #153: DNS-over-HTTPS fallback for the ArkTS realtime socket (@ohos.net.webSocket), the same
 * switch NetworkKMM's curl engine uses (mobile_doh_fallback_v0 → SlockDohFallbackPolicy → KNOI
 * `slockOhosRealtimeDohPolicy`).
 *
 * - The system resolver always goes first; DoH is asked only when it cannot resolve the host.
 * - Only the built-in providers below, queried at their fixed addresses (the providers' certificates
 *   cover those IPs, so TLS is verified as usual and the DoH lookup never touches the system resolver).
 *   Mirrors KuiklyBase-components curl_wrapper.cpp kDohProviders.
 * - The answer is installed with connection.addCustomDnsRule for the realtime host only, just for
 *   the connect, and removed as soon as the socket opens or fails. The WebSocket URL keeps the
 *   hostname, so SNI and certificate checks for the realtime host are unchanged.
 */

interface DohProvider {
  name: string;
  url: (host: string) => string;
  headers: Record<string, string>;
}

const DOH_PROVIDERS: Record<string, DohProvider> = {
  'alidns': {
    name: 'alidns',
    url: (host: string) => `https://223.5.5.5/resolve?name=${encodeURIComponent(host)}&type=1`,
    headers: { 'accept': 'application/dns-json' },
  },
  'cloudflare': {
    name: 'cloudflare',
    url: (host: string) => `https://1.1.1.1/dns-query?name=${encodeURIComponent(host)}&type=A`,
    headers: { 'accept': 'application/dns-json' },
  },
};

const DOH_TIMEOUT_MS = 5000;
const IPV4 = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/;

// mobile_doh_fallback_v0 defaults on; SlockDohFallbackPolicy overwrites this on its first publish
// (and whenever the server turns it off), so a publish that raced KNOI registration stays default-on.
let activeProviders: string[] = ['alidns', 'cloudflare'];

/** KNOI provider `slockOhosRealtimeDohPolicy`, fed by SlockDohFallbackPolicy. */
export class SlockOhosRealtimeDohPolicySink {
  setDohProviders(providerNames: string): boolean {
    activeProviders = providerNames.split(',')
      .map((name: string) => name.trim())
      .filter((name: string) => DOH_PROVIDERS[name] !== undefined);
    console.info(`[OhosSocketIo] doh_policy providers=${activeProviders.length > 0 ? activeProviders.join(',') : 'none'}`);
    return true;
  }
}

/** A host rule installed for one connect attempt; release it when the socket opens or fails. */
export class SlockOhosRealtimeDnsRule {
  private released: boolean = false;

  constructor(readonly host: string) {}

  release(): void {
    if (this.released) {
      return;
    }
    this.released = true;
    connection.removeCustomDnsRule(this.host).catch((error: Error) => {
      console.warn(`[OhosSocketIo] doh_rule_remove_failed ${error.message}`);
    });
  }
}

/**
 * Resolves [host] for a realtime connect. Returns undefined when nothing is needed (the system
 * resolver works, DoH is off, or every provider failed: the connect then fails as before);
 * otherwise a rule that must be released.
 */
export async function prepareRealtimeDnsRule(host: string): Promise<SlockOhosRealtimeDnsRule | undefined> {
  if (host.length === 0 || IPV4.test(host)) {
    return undefined;
  }
  try {
    const addresses = await connection.getAddressesByName(host);
    if (addresses.length > 0) {
      return undefined;
    }
  } catch (_error) {
    // Unresolved by the system resolver: fall through to DoH.
  }
  const providers = activeProviders.slice();
  if (providers.length === 0) {
    return undefined;
  }
  for (const name of providers) {
    const provider = DOH_PROVIDERS[name];
    const ips = await queryDoh(provider, host);
    if (ips.length === 0) {
      continue;
    }
    try {
      await connection.addCustomDnsRule(host, ips);
      console.info(`[OhosSocketIo] realtime_dns doh_fallback_success provider=${name} addresses=${ips.length}`);
      return new SlockOhosRealtimeDnsRule(host);
    } catch (error) {
      console.warn(`[OhosSocketIo] doh_rule_add_failed provider=${name} ${(error as Error).message}`);
      return undefined;
    }
  }
  console.warn(`[OhosSocketIo] realtime_dns doh_fallback_failure providers=${providers.join(',')}`);
  return undefined;
}

async function queryDoh(provider: DohProvider, host: string): Promise<string[]> {
  const request = http.createHttp();
  try {
    const response = await request.request(provider.url(host), {
      method: http.RequestMethod.GET,
      header: provider.headers,
      connectTimeout: DOH_TIMEOUT_MS,
      readTimeout: DOH_TIMEOUT_MS,
      expectDataType: http.HttpDataType.STRING,
    });
    if (response.responseCode !== 200) {
      return [];
    }
    return parseDohJsonAnswers(response.result as string);
  } catch (_error) {
    return [];
  } finally {
    request.destroy();
  }
}

interface DohJsonAnswer {
  type?: number;
  data?: string;
}

interface DohJsonResponse {
  Status?: number;
  Answer?: DohJsonAnswer[];
}

/** IPv4 A records from a DNS JSON response (RFC 8427 style, as both providers serve it). */
export function parseDohJsonAnswers(body: string): string[] {
  try {
    const parsed = JSON.parse(body) as DohJsonResponse;
    if (parsed.Status !== 0 || parsed.Answer === undefined) {
      return [];
    }
    return parsed.Answer
      .filter((answer: DohJsonAnswer) => answer.type === 1 && typeof answer.data === 'string' && IPV4.test(answer.data))
      .map((answer: DohJsonAnswer) => answer.data as string);
  } catch (_error) {
    return [];
  }
}
