import { NativeModules } from 'react-native';

type GlucoseConfig =
    | { source: 'dexcom'; username: string; password: string }
    | { source: 'xdripSync'; groupKey: string }
    | { source: 'nightscout'; url: string };

export type NativeGlucoseReading = {
    value: number;
    timestampMillis: number;
    trend?: string | null;
    source: GlucoseConfig['source'];
};

type GlucoseSyncModule = {
    saveConfig(configJson: string): void;
    start(): void;
    stop(): void;
    clear(): void;
    enableKeepScreenOn(): void;
    disableKeepScreenOn(): void;
    refreshNow(): Promise<number>;
    getConfig(): Promise<GlucoseConfig | null>;
    getLatest(): Promise<NativeGlucoseReading | null>;
    getHistory(minutes: number): Promise<NativeGlucoseReading[]>;
    getStatus(): Promise<{
        lastSyncAt: number;
        lastSuccessAt: number;
        lastError?: string | null;
        xdripDebug?: string | null;
    }>;
};

export const GlucoseSync = NativeModules.GlucoseSync as GlucoseSyncModule | undefined;

export function startNativeGlucoseSync(config: GlucoseConfig) {
    if (!GlucoseSync) {
        return;
    }

    GlucoseSync.saveConfig(JSON.stringify(config));
    GlucoseSync.start();
}

export function clearNativeGlucoseSync() {
    GlucoseSync?.clear();
}

export function enableKeepScreenOn() {
    GlucoseSync?.enableKeepScreenOn();
}

export function disableKeepScreenOn() {
    GlucoseSync?.disableKeepScreenOn();
}
