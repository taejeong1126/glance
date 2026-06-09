import { NextRequest, NextResponse } from 'next/server';

type GlucoseConfig =
    | {
          source: 'dexcom';
          username: string;
          password: string;
      }
    | {
          source: 'xdripSync';
          groupKey: string;
      }
    | {
          source: 'nightscout';
          url: string;
      };

declare global {
    var glanceSetupSessions: Map<string, GlucoseConfig> | undefined;
}

const sessions = globalThis.glanceSetupSessions ?? new Map<string, GlucoseConfig>();

globalThis.glanceSetupSessions = sessions;

export async function GET(
    _request: NextRequest,
    context: {
        params: Promise<{
            sessionId: string;
        }>;
    }
) {
    const { sessionId } = await context.params;
    const config = sessions.get(sessionId);

    if (!config) {
        return NextResponse.json({
            status: 'pending'
        });
    }

    sessions.delete(sessionId);

    return NextResponse.json({
        status: 'complete',
        config
    });
}

export async function POST(
    request: NextRequest,
    context: {
        params: Promise<{
            sessionId: string;
        }>;
    }
) {
    const { sessionId } = await context.params;
    const body = (await request.json()) as Partial<GlucoseConfig>;

    if (body.source === 'dexcom' && body.username && body.password) {
        sessions.set(sessionId, {
            source: 'dexcom',
            username: body.username,
            password: body.password
        });

        return NextResponse.json({
            status: 'saved'
        });
    }

    if (body.source === 'nightscout' && body.url) {
        sessions.set(sessionId, {
            source: 'nightscout',
            url: body.url
        });

        return NextResponse.json({
            status: 'saved'
        });
    }

    if (body.source === 'xdripSync' && body.groupKey) {
        sessions.set(sessionId, {
            source: 'xdripSync',
            groupKey: body.groupKey
        });

        return NextResponse.json({
            status: 'saved'
        });
    }

    return NextResponse.json(
        {
            error: 'invalid_config'
        },
        {
            status: 400
        }
    );
}
