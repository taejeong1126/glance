import { useEffect, useState } from 'react';
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, useWindowDimensions, View } from 'react-native';
import * as Keychain from 'react-native-keychain';

import packageJson from '../package.json';
import GlucoseGraph from './components/GlucoseGraph';
import { GlucoseSync, clearNativeGlucoseSync, startNativeGlucoseSync, type NativeGlucoseReading } from './native/glucose-sync';
import AppInfoScreen from './screens/app-info-screen';
import SetupScreen from './screens/setup-screen';
import { formatKstUpdatedAt } from './utils/time';

type GlucoseConfig =
    | { source: 'dexcom'; username: string; password: string }
    | { source: 'xdripSync'; groupKey: string }
    | { source: 'nightscout'; url: string };

const SETUP_BASE_URL = 'https://glance.taejeong.xyz';
const GRAPH_RANGES = [30, 60, 120, 240] as const;
const STALE_READING_MILLIS = 15 * 60 * 1000;
const CACHE_REFRESH_MILLIS = 5_000;
const GRAPH_HISTORY_PADDING_MINUTES = 10;

function clamp(value: number, min: number, max: number) {
    return Math.min(max, Math.max(min, value));
}

function formatDelta(reading: NativeGlucoseReading | null, history: NativeGlucoseReading[]) {
    if (!reading || history.length < 2) {
        return '0.0 mg/dL/min';
    }

    const previous = [...history].reverse().find((item) => item.timestampMillis < reading.timestampMillis);

    if (!previous) {
        return '0.0 mg/dL/min';
    }

    const minutes = (reading.timestampMillis - previous.timestampMillis) / 60_000;

    if (minutes <= 0) {
        return '0.0 mg/dL/min';
    }

    const delta = (reading.value - previous.value) / minutes;

    return `${delta.toFixed(1)} mg/dL/min`;
}

function App() {
    const { width, height } = useWindowDimensions();
    const [sessionId] = useState(() => `${Math.random().toString(36).slice(2, 10)}`);
    const [config, setConfig] = useState<GlucoseConfig | null>(null);
    const [reading, setReading] = useState<NativeGlucoseReading | null>(null);
    const [isLoading, setIsLoading] = useState(true);
    const [dataMessage, setDataMessage] = useState('데이터 대기 중');
    const [confirmDisconnect, setConfirmDisconnect] = useState(false);
    const [showDeveloperInfo, setShowDeveloperInfo] = useState(false);
    const [history, setHistory] = useState<NativeGlucoseReading[]>([]);
    const [rangeIndex, setRangeIndex] = useState(1);

    useEffect(() => {
        let isMounted = true;

        const checkStoredConfig = async () => {
            let storedConfig: GlucoseConfig | null = null;

            try {
                const dexcom = await Keychain.getGenericPassword({
                    service: 'glance.dexcom'
                });

                if (dexcom && dexcom.username && dexcom.password) {
                    storedConfig = {
                        source: 'dexcom',
                        username: dexcom.username,
                        password: dexcom.password
                    };
                }

                if (!storedConfig) {
                    const xdripSync = await Keychain.getGenericPassword({
                        service: 'glance.xdripSync'
                    });

                    if (xdripSync && xdripSync.password) {
                        storedConfig = {
                            source: 'xdripSync',
                            groupKey: xdripSync.password
                        };
                    }
                }

                if (!storedConfig) {
                    const nightscout = await Keychain.getGenericPassword({
                        service: 'glance.nightscout'
                    });

                    if (nightscout && nightscout.password) {
                        storedConfig = {
                            source: 'nightscout',
                            url: nightscout.password
                        };
                    }
                }
            } catch {
                storedConfig = null;
            }

            if (!isMounted) {
                return;
            }

            if (storedConfig) {
                startNativeGlucoseSync(storedConfig);
            }

            setConfig(storedConfig);
            setIsLoading(false);
        };

        checkStoredConfig();

        return () => {
            isMounted = false;
        };
    }, []);

    useEffect(() => {
        if (isLoading || config) {
            return;
        }

        let isMounted = true;

        const pollSetup = async () => {
            try {
                const response = await fetch(`${SETUP_BASE_URL}/api/setup/${sessionId}`);

                if (!response.ok) {
                    return;
                }

                const result = (await response.json()) as
                    | {
                          status: 'pending';
                      }
                    | {
                          status: 'complete';
                          config: GlucoseConfig;
                      };

                if (!isMounted || result.status !== 'complete') {
                    return;
                }

                if (result.config.source === 'dexcom') {
                    await Keychain.setGenericPassword(result.config.username, result.config.password, {
                        service: 'glance.dexcom'
                    });
                }

                if (result.config.source === 'nightscout') {
                    await Keychain.setGenericPassword('url', result.config.url, {
                        service: 'glance.nightscout'
                    });
                }

                if (result.config.source === 'xdripSync') {
                    await Keychain.setGenericPassword('groupKey', result.config.groupKey, {
                        service: 'glance.xdripSync'
                    });
                }

                startNativeGlucoseSync(result.config);
                setConfig(result.config);
                setReading(null);
                setDataMessage('데이터 동기화 중');
                setConfirmDisconnect(false);
                setShowDeveloperInfo(false);
            } catch {
                return;
            }
        };

        pollSetup();
        const intervalId = setInterval(pollSetup, 5000);

        return () => {
            isMounted = false;
            clearInterval(intervalId);
        };
    }, [config, isLoading, sessionId]);

    useEffect(() => {
        const glucoseSync = GlucoseSync;

        if (!config || !glucoseSync) {
            if (config) {
                setDataMessage('네이티브 동기화 없음');
            }
            return;
        }

        let isMounted = true;

        glucoseSync.refreshNow().catch(() => null);

        const loadNativeHistory = async () => {
            try {
                const nativeHistory = await glucoseSync.getHistory(Math.min(240, GRAPH_RANGES[rangeIndex] + GRAPH_HISTORY_PADDING_MINUTES));
                const status = await glucoseSync.getStatus();
                const latest = nativeHistory[nativeHistory.length - 1];

                if (!isMounted) {
                    return;
                }

                setHistory(nativeHistory);

                if (latest) {
                    setReading(latest);
                    setDataMessage('');
                    return;
                }

                setReading(null);
                const debugText = status.xdripDebug ? ` (${status.xdripDebug})` : '';
                setDataMessage(status.lastError ? `동기화 실패 ${status.lastError}${debugText}` : `데이터 동기화 중${debugText}`);
            } catch {
                if (isMounted) {
                    setDataMessage('네이티브 캐시 읽기 실패');
                }
                return;
            }
        };

        loadNativeHistory();
        const intervalId = setInterval(loadNativeHistory, CACHE_REFRESH_MILLIS);

        return () => {
            isMounted = false;
            clearInterval(intervalId);
        };
    }, [config, rangeIndex]);

    const disconnect = async () => {
        try {
            await Keychain.resetGenericPassword({
                service: 'glance.dexcom'
            });
            await Keychain.resetGenericPassword({
                service: 'glance.nightscout'
            });
            await Keychain.resetGenericPassword({
                service: 'glance.xdripSync'
            });
        } catch {
            return;
        }

        setConfig(null);
        setReading(null);
        setHistory([]);
        setConfirmDisconnect(false);
        setShowDeveloperInfo(false);
        setDataMessage('데이터 대기 중');
        clearNativeGlucoseSync();
    };

    if (isLoading) {
        return (
            <View style={styles.container}>
                <ActivityIndicator color="#ffffff" />
            </View>
        );
    }

    if (!config) {
        return <SetupScreen setupUrl={`${SETUP_BASE_URL}/setup?code=${encodeURIComponent(sessionId)}`} />;
    }

    if (showDeveloperInfo) {
        return <AppInfoScreen onBack={() => setShowDeveloperInfo(false)} />;
    }

    const contentScale = clamp(Math.min(width / 220, height / 280), 0.75, 1.3);
    const scaleSize = (size: number, min: number, max: number) => clamp(size * contentScale, min, max);
    const readingContentWidth = 250 * contentScale;
    const readingContentHeight = 230 * contentScale;
    const graphWidth = 250 * contentScale;
    const graphHeight = 120 * contentScale;
    const menuButtonHeight = scaleSize(40, 34, 52);
    const connectionTitleSize = scaleSize(12, 10, 15);
    const connectionValueSize = scaleSize(10, 8.5, 13);
    const menuButtonTextSize = scaleSize(11, 9.5, 14);
    const versionTextSize = scaleSize(9.5, 8, 12);
    const sourceTextSize = scaleSize(18, 14, 24);
    const updatedTextSize = scaleSize(10.5, 9, 14);
    const glucoseTextSize = scaleSize(60, 44, 78);
    const deltaTextSize = scaleSize(11, 9.5, 14);
    const statusTextSize = scaleSize(17, 14, 23);
    const urlTextSize = scaleSize(11, 9.5, 14);
    const isReadingStale = reading ? Date.now() - reading.timestampMillis > STALE_READING_MILLIS : false;
    const connectedSource = config.source === 'nightscout' ? 'Nightscout' : config.source === 'xdripSync' ? 'xDrip Sync' : 'Dexcom';

    return (
        <ScrollView contentContainerStyle={styles.scrollContent} showsVerticalScrollIndicator={false}>
            <View style={[styles.mainPage, { minHeight: height }]}>
                <View style={[styles.readingContent, { height: readingContentHeight, width: readingContentWidth }]}>
                    {reading ? (
                        <>
                            <Text
                                style={[
                                    styles.updatedText,
                                    { fontSize: updatedTextSize, lineHeight: updatedTextSize * 1.9 },
                                    isReadingStale && styles.staleUpdatedText
                                ]}
                            >
                                {formatKstUpdatedAt(reading.timestampMillis)}
                            </Text>
                            <Text
                                style={[styles.glucoseText, { fontSize: glucoseTextSize, lineHeight: glucoseTextSize * 0.92, transform: [{ scaleY: 0.85 }] }]}
                            >
                                {reading.value}
                            </Text>
                            <Text style={[styles.deltaText, { fontSize: deltaTextSize, lineHeight: deltaTextSize * 1.8 }]}>
                                {formatDelta(reading, history)}
                            </Text>
                            <Pressable
                                style={[styles.graph, { height: graphHeight, width: graphWidth }]}
                                onPress={() => setRangeIndex((value) => (value + 1) % GRAPH_RANGES.length)}
                            >
                                <View style={{ transform: [{ scale: contentScale }] }}>
                                    <GlucoseGraph readings={history} rangeMinutes={GRAPH_RANGES[rangeIndex]} />
                                </View>
                            </Pressable>
                        </>
                    ) : (
                        <View style={styles.statusContent}>
                            <Text style={[styles.sourceText, { fontSize: sourceTextSize }]}>
                                {config.source === 'nightscout' ? 'Nightscout' : config.source === 'xdripSync' ? 'xDrip Sync' : 'Dexcom'}
                            </Text>
                            <Text style={[styles.statusText, { fontSize: statusTextSize }]}>{dataMessage}</Text>
                            {config.source === 'nightscout' ? <Text style={[styles.urlText, { fontSize: urlTextSize }]}>{config.url}</Text> : null}
                        </View>
                    )}
                </View>
            </View>
            <View style={styles.menuSection}>
                <Text style={[styles.connectionTitle, { fontSize: connectionTitleSize }]}>연결 정보</Text>
                <Text style={[styles.connectionValue, { fontSize: connectionValueSize }]}>{connectedSource}</Text>
                <Pressable style={[styles.menuButton, { height: menuButtonHeight }]} onPress={() => setShowDeveloperInfo(true)}>
                    <Text style={[styles.menuButtonText, { fontSize: menuButtonTextSize }]}>개발자 정보</Text>
                </Pressable>
                <Pressable
                    style={[styles.menuButton, { height: menuButtonHeight }, confirmDisconnect && styles.disconnectConfirmButton]}
                    onPress={async () => {
                        if (!confirmDisconnect) {
                            setConfirmDisconnect(true);
                            return;
                        }

                        await disconnect();
                    }}
                >
                    <Text style={[styles.menuButtonText, { fontSize: menuButtonTextSize }, confirmDisconnect && styles.disconnectConfirmText]}>
                        {confirmDisconnect ? '한 번 더 눌러 연결 해제' : '연결 해제'}
                    </Text>
                </Pressable>
                <Text style={[styles.versionText, { fontSize: versionTextSize }]}>Glance v{packageJson.version}</Text>
            </View>
        </ScrollView>
    );
}

const styles = StyleSheet.create({
    container: {
        flex: 1,
        alignItems: 'center',
        justifyContent: 'space-between',
        backgroundColor: '#050505'
    },
    scrollContent: {
        alignItems: 'center',
        backgroundColor: '#050505',
        flexGrow: 1,
        minHeight: '100%',
        paddingBottom: 28
    },
    mainPage: {
        alignItems: 'center',
        justifyContent: 'center',
        width: '100%'
    },
    readingContent: {
        alignItems: 'center',
        height: 230,
        justifyContent: 'center',
        width: 250
    },
    menuSection: {
        alignItems: 'center',
        justifyContent: 'center',
        minHeight: 190,
        paddingHorizontal: 28,
        paddingVertical: 8,
        width: '100%'
    },
    connectionTitle: {
        color: '#ffffff',
        fontSize: 12,
        fontWeight: '700',
        textAlign: 'center',
        width: '100%'
    },
    connectionValue: {
        color: '#D1D8E3',
        fontSize: 10,
        marginBottom: 10,
        marginTop: 3,
        textAlign: 'center',
        width: '100%'
    },
    menuButton: {
        alignItems: 'center',
        backgroundColor: '#55555b',
        borderRadius: 22,
        height: 40,
        justifyContent: 'center',
        marginBottom: 6,
        width: '100%'
    },
    menuButtonText: {
        color: '#ffffff',
        fontSize: 11,
        fontWeight: '600',
        textAlign: 'center',
        width: '100%'
    },
    disconnectConfirmButton: {
        borderColor: '#ef4444',
        borderWidth: 1
    },
    disconnectConfirmText: {
        color: '#f87171'
    },
    versionText: {
        color: '#D1D8E3',
        fontSize: 9.5,
        marginTop: 0,
        textAlign: 'center',
        width: '100%'
    },
    sourceText: {
        color: '#ffffff',
        fontSize: 18,
        fontWeight: '600',
        textAlign: 'center',
        width: '100%'
    },
    statusContent: {
        alignItems: 'center',
        justifyContent: 'center',
        width: '100%'
    },
    updatedText: {
        color: '#b8b8b8',
        fontSize: 10.5,
        fontWeight: '500',
        lineHeight: 20,
        textAlign: 'center',
        width: '100%'
    },
    staleUpdatedText: {
        color: '#ef4444'
    },
    glucoseText: {
        color: '#ffffff',
        fontSize: 60,
        fontWeight: '600',
        lineHeight: 55,
        textAlign: 'center',
        width: '100%'
    },
    deltaText: {
        color: '#b8b8b8',
        fontSize: 11,
        fontWeight: '600',
        lineHeight: 20,
        marginBottom: 4,
        textAlign: 'center',
        width: '100%'
    },
    messageText: {
        color: '#b8b8b8',
        fontSize: 10,
        marginTop: 4,
        textAlign: 'center'
    },
    statusText: {
        color: '#7dd3fc',
        fontSize: 17,
        fontWeight: '700',
        marginTop: 6,
        textAlign: 'center',
        width: '100%'
    },
    urlText: {
        color: '#C2CCD8',
        fontSize: 11,
        marginTop: 4,
        maxWidth: 160,
        textAlign: 'center',
        width: '100%'
    },
    graph: {
        alignItems: 'center',
        alignSelf: 'center',
        height: 120,
        justifyContent: 'center',
        marginTop: 0,
        width: 250
    },
    rangeText: {
        color: '#94a3b8',
        fontSize: 9,
        fontWeight: '700',
        marginTop: -10
    }
});

export default App;
