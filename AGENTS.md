# AGENTS.md

## Project

Glance is a Wear OS application built with React Native and TypeScript.

The primary purpose of Glance is to display continuous glucose monitoring (CGM) data on Wear OS smartwatches.

Supported data sources:

-   Dexcom Share
-   CareSens Air
-   Nightscout

Target devices:

-   Samsung Galaxy Watch series
-   Wear OS 4+
-   Wear OS 5+

---

## Architecture

Repository structure:

```text
/
├─ android/
├─ src/
├─ App.tsx
├─ package.json
└─ setup-web/
```

### Mobile App

Technology:

-   React Native
-   TypeScript

Responsibilities:

-   Display current glucose value
-   Display glucose trend
-   Display glucose graph
-   Manage settings
-   QR setup flow
-   Future support for Tiles
-   Future support for Watch Faces
-   Future support for Complications

### Setup Web

Location:

```text
setup-web/
```

Technology:

-   Next.js
-   TypeScript
-   Vercel

Responsibilities:

-   Configuration UI
-   QR onboarding flow
-   Temporary session handling
-   Device pairing

No glucose data should be permanently stored on the web service.

---

## QR Setup Flow

1. Watch generates a session ID.
2. Watch displays a QR code.
3. User scans the QR code with a phone.
4. User enters configuration values.
5. Configuration is temporarily stored.
6. Watch polls for configuration completion.
7. Watch stores configuration locally.
8. Temporary server-side configuration is deleted.

The server acts only as a temporary transport layer.

---

## Coding Standards

### General

-   Use TypeScript.
-   Prefer functional components.
-   Prefer React Hooks.
-   Avoid class components.
-   Keep components small and focused.

### React Native

Preferred:

```tsx
const styles = StyleSheet.create({...});
```

Avoid excessive inline styles.

Use:

-   react-native-safe-area-context
-   react-native-svg

when appropriate.

### File Naming

Components:

```text
CurrentGlucoseCard.tsx
TrendIndicator.tsx
SetupQrScreen.tsx
```

Hooks:

```text
useNightscout.ts
useGlucoseData.ts
```

Types:

```text
glucose.ts
settings.ts
```

---

## Data Models

Example:

```ts
export interface GlucoseReading {
    value: number;
    trend: string;
    timestamp: string;
}
```

---

## UI Principles

Wear OS screens are small.

Priorities:

1. Current glucose value
2. Trend direction
3. Delta value
4. Graph

Avoid unnecessary UI elements.

Prefer large readable text.

Design for round displays first.

---

## Development Environment

Primary platform:

-   Windows 11

Primary editor:

-   VS Code

Device testing:

-   Samsung Galaxy Watch5

Android Studio is not required for development.

ADB over Wi-Fi is supported.

---

## Performance Requirements

-   Minimize unnecessary renders.
-   Minimize network usage.
-   Avoid frequent polling when data is unchanged.
-   Optimize battery consumption.

Glucose updates should not significantly impact watch battery life.

---

## Security

Never commit:

-   API secrets
-   Access tokens
-   Environment files

Use:

```text
.env.local
.env
```

for local development only.

Sensitive configuration should be stored securely on-device.

---

## Agent Guidelines

When modifying code:

-   Preserve TypeScript types.
-   Avoid introducing unnecessary dependencies.
-   Keep the setup flow simple.
-   Prioritize reliability over feature complexity.
-   Optimize for Wear OS usability.

The primary goal is fast, reliable glucose visibility on the watch.
