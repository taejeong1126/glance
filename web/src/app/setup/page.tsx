'use client';

import { useRouter, useSearchParams } from 'next/navigation';
import { Suspense, useState } from 'react';

type Source = 'nightscout' | 'dexcom' | 'xdripSync';

const sources: Array<{ value: Source; label: string }> = [
    { value: 'nightscout', label: 'Nightscout' },
    { value: 'dexcom', label: 'Dexcom' },
    { value: 'xdripSync', label: 'xDrip Sync' }
];

function SetupForm() {
    const router = useRouter();
    const searchParams = useSearchParams();
    const sessionId = searchParams.get('code') ?? '';
    const [source, setSource] = useState<Source>('nightscout');
    const [url, setUrl] = useState('');
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');
    const [groupKey, setGroupKey] = useState('');
    const [status, setStatus] = useState<'idle' | 'saving' | 'error'>('idle');
    const [message, setMessage] = useState('');

    const changeSource = (nextSource: Source) => {
        setSource(nextSource);
        setStatus('idle');
        setMessage('');
    };

    const saveSetup = async () => {
        if (!sessionId) {
            setStatus('error');
            setMessage('세션 ID가 없습니다. 워치의 QR 코드를 다시 스캔해 주세요.');
            return;
        }

        if (source === 'nightscout' && !url.trim()) {
            setStatus('error');
            setMessage('Nightscout URL을 입력해 주세요.');
            return;
        }

        if (source === 'dexcom' && (!username.trim() || !password.trim())) {
            setStatus('error');
            setMessage('Dexcom ID와 비밀번호를 입력해 주세요.');
            return;
        }

        if (source === 'xdripSync' && !groupKey.trim()) {
            setStatus('error');
            setMessage('xDrip+ 핸드셋 그룹 키를 입력해 주세요.');
            return;
        }

        setStatus('saving');
        setMessage('');

        const config =
            source === 'nightscout'
                ? { source, url: url.trim().replace(/\/+$/, '') }
                : source === 'dexcom'
                ? { source, username: username.trim(), password: password.trim() }
                : { source, groupKey: groupKey.trim() };

        try {
            const response = await fetch(`/api/setup/${encodeURIComponent(sessionId)}`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify(config)
            });

            if (!response.ok) {
                setStatus('error');
                setMessage('설정을 전송하지 못했습니다. 잠시 후 다시 시도해 주세요.');
                return;
            }

            router.replace('/setup/complete');
        } catch {
            setStatus('error');
            setMessage('서버에 연결하지 못했습니다. 네트워크 상태를 확인해 주세요.');
        }
    };

    return (
        <main className="min-h-screen bg-white text-zinc-950">
            <div className="mx-auto w-full max-w-xl px-5 pb-10 pt-7 sm:px-8 sm:pt-10">
                <header className="border-b border-zinc-200 pb-5">
                    <p className="text-sm font-semibold text-zinc-500">Glance</p>
                    <h1 className="mt-1 text-2xl font-bold tracking-tight">워치 연결</h1>
                    <p className="mt-2 text-sm leading-6 text-zinc-600">혈당 데이터를 가져올 서비스를 선택하세요.</p>
                </header>

                <section className="pt-6">
                    <h2 className="mb-3 text-sm font-semibold text-zinc-900">데이터 소스</h2>

                    {!sessionId ? (
                        <p className="mb-5 rounded-lg bg-red-50 px-3 py-2.5 text-sm leading-6 text-red-700">워치의 QR 코드를 다시 스캔해 주세요.</p>
                    ) : null}

                    <div className="grid grid-cols-3 rounded-lg bg-zinc-100 p-1">
                        {sources.map((item) => (
                            <button
                                key={item.value}
                                className={
                                    source === item.value
                                        ? 'rounded-md bg-white px-2 py-2.5 text-sm font-semibold text-zinc-950 shadow-sm'
                                        : 'rounded-md px-2 py-2.5 text-sm font-medium text-zinc-500 transition-colors hover:text-zinc-900'
                                }
                                type="button"
                                onClick={() => changeSource(item.value)}
                            >
                                {item.label}
                            </button>
                        ))}
                    </div>

                    <div className="mt-7 space-y-5">
                        {source === 'nightscout' ? (
                            <>
                                <Field label="Nightscout URL" hint="사이트 주소 끝의 /는 자동으로 제거됩니다.">
                                    <input
                                        className="h-11 w-full rounded-lg border border-zinc-300 bg-white px-3 text-sm outline-none placeholder:text-zinc-400 focus:border-zinc-950"
                                        inputMode="url"
                                        onChange={(event) => setUrl(event.target.value)}
                                        placeholder="https://example.com"
                                        type="url"
                                        value={url}
                                    />
                                </Field>

                                <div className="rounded-xl border border-zinc-200 bg-zinc-50 px-4 py-3 text-sm leading-6 text-zinc-700">
                                    <p className="font-semibold text-zinc-900">Nightscout 설정 안내</p>

                                    <p className="mt-1">Nightscout 사이트에서 혈당 데이터를 인터넷으로 수신해 워치에 표시합니다.</p>

                                    <p className="mt-2">
                                        Glance에서 사용하려면 Nightscout 사이트가 <strong>API Secret 없이 혈당 데이터를 조회할 수 있도록</strong> 설정되어
                                        있어야 합니다.
                                    </p>

                                    <p className="mt-2">
                                        사용 중인 <strong>Nightscout 주소</strong>를 입력해 주세요.
                                    </p>
                                </div>
                            </>
                        ) : null}

                        {source === 'dexcom' ? (
                            <>
                                <Field label="Dexcom ID">
                                    <input
                                        autoComplete="username"
                                        className="h-11 w-full rounded-lg border border-zinc-300 bg-white px-3 text-sm outline-none focus:border-zinc-950"
                                        onChange={(event) => setUsername(event.target.value)}
                                        type="text"
                                        value={username}
                                    />
                                </Field>
                                <Field label="Dexcom 비밀번호">
                                    <input
                                        autoComplete="current-password"
                                        className="h-11 w-full rounded-lg border border-zinc-300 bg-white px-3 text-sm outline-none focus:border-zinc-950"
                                        onChange={(event) => setPassword(event.target.value)}
                                        type="password"
                                        value={password}
                                    />
                                </Field>

                                <div className="rounded-xl border border-zinc-200 bg-zinc-50 px-4 py-3 text-sm leading-6 text-zinc-700">
                                    <p className="font-semibold text-zinc-900">Dexcom Share 설정 안내</p>

                                    <p className="mt-1">Dexcom Share 데이터를 인터넷으로 수신해 워치에 표시합니다.</p>

                                    <p className="mt-2">
                                        먼저 Dexcom 앱에서 <strong>공유 기능</strong>을 켜고, 혈당을 확인할 수 있는 <strong>Dexcom Follow 계정</strong> 정보를
                                        입력해 주세요.
                                    </p>

                                    <p className="mt-2">
                                        현재 <strong>미국 외 지역</strong>의 Dexcom Share 계정만 사용할 수 있습니다.
                                    </p>
                                </div>
                            </>
                        ) : null}

                        {source === 'xdripSync' ? (
                            <>
                                <Field label="xDrip+ 그룹 키" hint="xDrip+ Sync에서 사용하는 그룹 키를 입력하세요.">
                                    <input
                                        autoCapitalize="characters"
                                        autoComplete="off"
                                        className="h-11 w-full rounded-lg border border-zinc-300 bg-white px-3 font-mono text-sm outline-none placeholder:text-zinc-400 focus:border-zinc-950"
                                        onChange={(event) => setGroupKey(event.target.value)}
                                        placeholder="예: DEA00CCE0D53CEA479AF4B6EB66282BF"
                                        spellCheck={false}
                                        type="text"
                                        value={groupKey}
                                    />
                                </Field>

                                <div className="rounded-xl border border-zinc-200 bg-zinc-50 px-4 py-3 text-sm leading-6 text-zinc-700">
                                    <p className="font-semibold text-zinc-900">xDrip+ Sync 설정 안내</p>

                                    <p className="mt-1">xDrip+ Sync는 xDrip+ Cloud 데이터를 인터넷으로 직접 수신하는 연동 방식입니다.</p>

                                    <p className="mt-1">
                                        xDrip+ 앱에서 <strong>User xDrip Cloud</strong>를 활성화한 뒤,
                                        <strong> Master</strong> 모드를 켜 주세요.
                                    </p>

                                    <p className="mt-2">
                                        소켓 통신 특성상 Dexcom Share, Nightscout보다 배터리 사용량이 많거나 수신이 불안정할 수 있으며, 경우에 따라{' '}
                                        <strong>15분 이상의 시간차</strong>가 발생할 수 있습니다.
                                    </p>

                                    <p className="mt-2">
                                        안정적인 혈당 수신을 원한다면 가능하면 <strong>Nightscout</strong> 연동 방식을 권장합니다.
                                    </p>
                                </div>
                            </>
                        ) : null}
                    </div>

                    {message ? <p className="mt-5 rounded-lg bg-red-50 px-3 py-2.5 text-sm leading-6 text-red-700">{message}</p> : null}

                    <button
                        className="mt-8 h-12 w-full rounded-lg bg-zinc-950 px-4 text-sm font-semibold text-white transition hover:bg-zinc-800 disabled:cursor-not-allowed disabled:opacity-50"
                        disabled={!sessionId || status === 'saving'}
                        type="button"
                        onClick={saveSetup}
                    >
                        {status === 'saving' ? '워치로 보내는 중...' : '워치로 보내기'}
                    </button>
                </section>
            </div>
        </main>
    );
}

function Field({ children, hint, label }: { children: React.ReactNode; hint?: string; label: string }) {
    return (
        <label className="block">
            <span className="mb-2 block text-sm font-semibold text-zinc-800">{label}</span>
            {children}
            {hint ? <span className="mt-2 block text-xs text-zinc-400">{hint}</span> : null}
        </label>
    );
}

export default function SetupPage() {
    return (
        <Suspense fallback={<main className="min-h-screen bg-zinc-50" />}>
            <SetupForm />
        </Suspense>
    );
}
