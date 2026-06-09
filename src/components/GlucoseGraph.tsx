import Svg, { Circle, Line, Text as SvgText } from 'react-native-svg';

import type { NativeGlucoseReading } from '../native/glucose-sync';
import { formatKstClock } from '../utils/time';

type Props = {
    readings: NativeGlucoseReading[];
    rangeMinutes: number;
};

const WIDTH = 250;
const HEIGHT = 120;
const GRAPH_LEFT = 22;
const GRAPH_RIGHT = 228;
const GRAPH_TOP = 0;
const GRAPH_BOTTOM = 72;
const HIGH_LINE = 180;
const LOW_LINE = 70;
const LOW_COLOR = '#ef4444';
const NORMAL_COLOR = '#2dd4bf';
const HIGH_COLOR = '#facc15';

type GraphPoint = {
    x: number;
    y: number;
    value: number;
};

function colorForValue(value: number) {
    if (value < LOW_LINE) {
        return LOW_COLOR;
    }

    if (value > HIGH_LINE) {
        return HIGH_COLOR;
    }

    return NORMAL_COLOR;
}

function splitSegmentAtThresholds(start: GraphPoint, end: GraphPoint) {
    const thresholds = [LOW_LINE, HIGH_LINE]
        .filter((threshold) => (start.value < threshold && end.value > threshold) || (start.value > threshold && end.value < threshold))
        .map((threshold) => {
            const ratio = (threshold - start.value) / (end.value - start.value);

            return {
                ratio,
                point: {
                    x: start.x + (end.x - start.x) * ratio,
                    y: start.y + (end.y - start.y) * ratio,
                    value: threshold
                }
            };
        })
        .sort((a, b) => a.ratio - b.ratio);

    const points = [start, ...thresholds.map(({ point }) => point), end];

    return points.slice(0, -1).map((point, index) => {
        const nextPoint = points[index + 1];
        const middleValue = (point.value + nextPoint.value) / 2;

        return {
            start: point,
            end: nextPoint,
            color: colorForValue(middleValue)
        };
    });
}

export default function GlucoseGraph({ readings, rangeMinutes }: Props) {
    const latestTimestamp = readings[readings.length - 1]?.timestampMillis ?? Date.now();
    const from = latestTimestamp - rangeMinutes * 60_000;
    const visibleReadings = readings.filter((reading) => reading.timestampMillis >= from && reading.timestampMillis <= latestTimestamp);
    const values = visibleReadings.map((reading) => reading.value);
    const minValue = Math.max(40, Math.min(...values, LOW_LINE) - 20);
    const maxValue = Math.min(400, Math.max(...values, HIGH_LINE) + 20);
    const valueRange = Math.max(1, maxValue - minValue);
    const innerWidth = GRAPH_RIGHT - GRAPH_LEFT;
    const innerHeight = GRAPH_BOTTOM - GRAPH_TOP;

    const pointFor = (reading: NativeGlucoseReading) => {
        const x = GRAPH_LEFT + ((reading.timestampMillis - from) / (rangeMinutes * 60_000)) * innerWidth;
        const y = GRAPH_TOP + (1 - (reading.value - minValue) / valueRange) * innerHeight;

        return { x, y, value: reading.value };
    };

    const graphPoints = visibleReadings.map(pointFor);
    const segments = graphPoints.slice(0, -1).flatMap((point, index) => splitSegmentAtThresholds(point, graphPoints[index + 1]));
    const latest = visibleReadings[visibleReadings.length - 1];
    const timeLabels = Array.from({ length: 5 }, (_, index) => {
        const ratio = index / 4;

        return {
            timestamp: from + rangeMinutes * 60_000 * ratio,
            x: GRAPH_LEFT + innerWidth * ratio,
            textAnchor: index === 0 ? 'start' : index === 4 ? 'end' : 'middle'
        } as const;
    });
    const yForValue = (value: number) => GRAPH_TOP + (1 - (value - minValue) / valueRange) * innerHeight;
    const lowY = yForValue(LOW_LINE);
    const highY = yForValue(HIGH_LINE);

    return (
        <Svg width={WIDTH} height={HEIGHT}>
            <Line x1={GRAPH_LEFT} y1={GRAPH_BOTTOM} x2={GRAPH_RIGHT} y2={GRAPH_BOTTOM} stroke="#27272a" strokeWidth="1" />
            <Line x1={GRAPH_LEFT} y1={GRAPH_TOP} x2={GRAPH_LEFT} y2={GRAPH_BOTTOM} stroke="#27272a" strokeWidth="1" />
            <Line x1={(GRAPH_LEFT + GRAPH_RIGHT) / 2} y1={GRAPH_TOP} x2={(GRAPH_LEFT + GRAPH_RIGHT) / 2} y2={GRAPH_BOTTOM} stroke="#1f2937" strokeWidth="1" />
            <Line x1={GRAPH_RIGHT} y1={GRAPH_TOP} x2={GRAPH_RIGHT} y2={GRAPH_BOTTOM} stroke="#1f2937" strokeWidth="1" />
            <Line x1={GRAPH_LEFT} y1={highY} x2={GRAPH_RIGHT} y2={highY} stroke={HIGH_COLOR} strokeDasharray="3 3" strokeOpacity="0.45" strokeWidth="1" />
            <Line x1={GRAPH_LEFT} y1={lowY} x2={GRAPH_RIGHT} y2={lowY} stroke={LOW_COLOR} strokeDasharray="3 3" strokeOpacity="0.45" strokeWidth="1" />
            <SvgText fill={HIGH_COLOR} fontSize="9" x={GRAPH_RIGHT + 4} y={highY + 3} textAnchor="start">
                180
            </SvgText>
            <SvgText fill={LOW_COLOR} fontSize="9" x={GRAPH_RIGHT + 4} y={lowY + 3} textAnchor="start">
                70
            </SvgText>
            {segments.map((segment, index) => (
                <Line
                    key={`${index}-${segment.start.x}`}
                    x1={segment.start.x}
                    y1={segment.start.y}
                    x2={segment.end.x}
                    y2={segment.end.y}
                    stroke={segment.color}
                    strokeLinecap="round"
                    strokeWidth="3"
                />
            ))}
            {latest ? (
                <Circle cx={pointFor(latest).x} cy={pointFor(latest).y} fill={colorForValue(latest.value)} r="2.8" stroke="#ffffff" strokeWidth="1" />
            ) : null}
            {!latest ? (
                <SvgText fill="#71717a" fontSize="11" x={(GRAPH_LEFT + GRAPH_RIGHT) / 2} y={48} textAnchor="middle">
                    no data
                </SvgText>
            ) : null}
            {timeLabels.map((label) => (
                <SvgText key={label.timestamp} fill="#D1D8E3" fontSize="10" x={label.x} y={92} textAnchor={label.textAnchor}>
                    {formatKstClock(label.timestamp)}
                </SvgText>
            ))}
        </Svg>
    );
}
