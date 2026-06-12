export default function NotFound() {
    return (
        <main className="min-h-screen bg-white text-zinc-950">
            <div className="mx-auto w-full max-w-xl px-5 pb-10 pt-7 sm:px-8 sm:pt-10">
                <p className="text-sm font-semibold text-zinc-500">Glance</p>

                <h1 className="mt-1 text-2xl font-bold tracking-tight">페이지를 찾을 수 없습니다</h1>

                <div className="mt-6 border-t border-zinc-200 pt-6">
                    <p className="text-base font-semibold">요청한 설정 페이지를 열 수 없습니다.</p>

                    <p className="mt-2 text-sm leading-6 text-zinc-600">
                        QR 코드가 만료되었거나 주소가 올바르지 않을 수 있습니다. 워치에서 새 QR 코드를 다시 표시한 뒤 휴대폰 카메라로 스캔해 주세요.
                    </p>
                </div>
            </div>
        </main>
    );
}
