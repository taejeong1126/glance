export default function SetupCompletePage() {
    return (
        <main className="min-h-screen bg-white text-zinc-950">
            <div className="mx-auto w-full max-w-xl px-5 pb-10 pt-7 sm:px-8 sm:pt-10">
                <p className="text-sm font-semibold text-zinc-500">Glance</p>

                <h1 className="mt-1 text-2xl font-bold tracking-tight">설정 전송 완료</h1>

                <div className="mt-6 border-t border-zinc-200 pt-6">
                    <p className="text-base font-semibold">워치에서 연결 정보를 확인하고 있습니다.</p>

                    <p className="mt-2 text-sm leading-6 text-zinc-600">입력한 설정 정보는 서버에 저장되지 않고, 워치로 전달된 뒤 연결 확인에만 사용됩니다.</p>

                    <p className="mt-2 text-sm leading-6 text-zinc-600">
                        처음 혈당 데이터를 수신하기까지 시간이 걸릴 수 있습니다. 설정이 정상적으로 적용되면 잠시 후 워치 화면에 혈당 데이터가 표시됩니다.
                    </p>

                    <p className="mt-2 text-sm leading-6 text-zinc-600">
                        Glance는 오픈소스로 공개되어 있으며, 소스 코드는{' '}
                        <a
                            className="font-medium text-zinc-950 underline underline-offset-4"
                            href="https://github.com/taejeong1126/glance"
                            rel="noreferrer"
                            target="_blank"
                        >
                            GitHub
                        </a>
                        에서 확인할 수 있습니다.
                    </p>

                    <p className="mt-2 text-sm leading-6 text-zinc-600">
                        혈당 데이터가 표시되지 않는 경우, 입력한 설정값과 워치의 인터넷 연결 상태를 확인해 주세요.
                    </p>

                    <p className="mt-6 text-sm text-zinc-500">이 페이지는 닫아도 됩니다.</p>
                </div>
            </div>
        </main>
    );
}
