export default function SetupCompletePage() {
    return (
        <main className="min-h-screen bg-white text-zinc-950">
            <div className="mx-auto w-full max-w-xl px-5 pb-10 pt-7 sm:px-8 sm:pt-10">
                <p className="text-sm font-semibold text-zinc-500">Glance</p>
                <h1 className="mt-1 text-2xl font-bold tracking-tight">전송 완료</h1>
                <div className="mt-6 border-t border-zinc-200 pt-6">
                    <p className="text-base font-semibold">워치에서 연결 정보를 확인하고 있습니다.</p>
                    <p className="mt-2 text-sm leading-6 text-zinc-600">잠시 후 워치 화면에 혈당 데이터가 표시됩니다.</p>
                    <p className="mt-6 text-sm text-zinc-500">이 페이지는 닫아도 됩니다.</p>
                </div>
            </div>
        </main>
    );
}
