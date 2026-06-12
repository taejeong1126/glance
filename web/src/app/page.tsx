'use client';

export default function Home() {
    return (
        <main className="min-h-screen bg-white text-zinc-950">
            <div className="mx-auto w-full max-w-xl px-5 pb-10 pt-7 sm:px-8 sm:pt-10">
                <p className="text-sm font-semibold text-zinc-500">Glance</p>

                <h1 className="mt-1 text-2xl font-bold tracking-tight">워치 설정 시작하기</h1>

                <div className="mt-6 border-t border-zinc-200 pt-6">
                    <p className="text-base font-semibold">휴대폰 카메라로 워치에 표시된 QR 코드를 스캔하세요.</p>

                    <p className="mt-2 text-sm leading-6 text-zinc-600">
                        QR 코드를 스캔하면 Glance 설정 페이지가 열리며, 혈당 데이터 수신 방식을 선택하고 워치에 필요한 정보를 전송할 수 있습니다.
                    </p>
                </div>
            </div>
        </main>
    );
}
