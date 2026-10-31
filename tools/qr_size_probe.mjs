// Проба размера QR кода камеры: оценивает длину текста CameraShareCode и версию QR
// (сколько модулей на сторону получается). Нужна только для оценки «крупности» кода.
import zlib from "node:zlib";

const DEFAULTS = {
    id: "", name: "", ipAddress: "",
    rtspPort: 554, httpPort: 80, onvifPort: 8080, dahuaPort: 37777,
    knownPorts: [],
    rtspPath: "/live/ch0", username: "", password: "",
    protocol: "ONVIF", ptzSpeed: 0.5, ptzRepeatMode: false, ptzInvertPan: false, ptzInvertTilt: false,
    focusAvailability: "AUTO", autoLowerQuality: true, snapshotThumbnail: false, dahuaApi: false,
    sdHttpArchive: false, hikvisionArchive: false, cameraAudioWithApp: true, dvripArchive: false,
    dvripPort: 34567, hipcamPort: 0, motionAlerts: false, showDateTime: true, clockPosition: "TOP_END",
    videoRotation: "NONE", hardwareRotation: "NONE", archiveBlockEnabled: true, archiveBlockCount: 10,
    archiveBlockHideOnMetered: false, autoSyncTime: true,
    streamProfiles: (() => { const a = []; for (let i = 1; i <= 8; i++) a.push({ id: "", name: `Пресет ${i}`, token: `${i}`, saved: false }); return a; })(),
    presets: (() => { const a = []; for (let i = 1; i <= 8; i++) a.push({ id: i, name: `Пресет ${i}`, token: `${i}`, saved: false }); return a; })(),
};

function camera(overrides) {
    return { ...structuredClone(DEFAULTS), presets: structuredClone(DEFAULTS.presets), ...overrides };
}

// То же, что CameraShareCode.encode: JSON → deflate → base64url без паддинга
function shareCode(cam, includeCredentials = true) {
    const json = structuredClone(cam);
    delete json.id;
    if (!includeCredentials) { delete json.username; delete json.password; }
    const activeIdx = (cam.streamProfiles || []).findIndex(p => p.id === cam.activeStreamProfileId);
    if (activeIdx >= 0) json.activeStream = activeIdx;
    delete json.activeStreamProfileId;
    (json.streamProfiles || []).forEach(p => delete p.id);
    for (const key of Object.keys(DEFAULTS)) {
        if (key === "name" || key === "ipAddress") continue;
        if (!(key in json)) continue;
        if (JSON.stringify(json[key]) === JSON.stringify(DEFAULTS[key])) delete json[key];
    }
    const packed = zlib.deflateSync(Buffer.from(JSON.stringify(json), "utf8"), { level: 9 });
    return { text: "DMIPCAM1:" + packed.toString("base64url"), jsonLen: JSON.stringify(json).length, packedLen: packed.length };
}

// Ёмкость байтового режима (байты) по версиям QR
const CAP_M = [0, 14, 26, 42, 62, 84, 106, 122, 152, 180, 213, 251, 287, 331, 362, 412, 450, 504, 560, 624, 666, 711, 779, 857, 911, 997, 1059, 1125, 1190, 1264, 1370, 1452, 1538, 1628, 1722, 1809, 1911, 1989, 2099, 2213, 2331];
const CAP_L = [0, 17, 32, 53, 78, 106, 134, 154, 192, 230, 271, 321, 367, 425, 458, 520, 586, 644, 718, 792, 858, 929, 1003, 1091, 1171, 1273, 1367, 1465, 1528, 1628, 1732, 1840, 1952, 2068, 2188, 2303, 2431, 2563, 2699, 2809, 2953];

function guessVersion(bytes, byecL) {
    const table = byecL ? CAP_L : CAP_M;
    for (let v = 1; v < table.length; v++) if (bytes <= table[v]) return v;
    return 40;
}

function report(label, cam) {
    const { text, jsonLen, packedLen } = shareCode(cam);
    const bytes = Buffer.byteLength(text, "utf8");
    const big = text.length > 1200;            // уровень коррекции в QrCodes.encode
    const version = guessVersion(bytes, big);
    const modules = 17 + 4 * version + 8;      // + поля (MARGIN 2 с каждой стороны)
    const modulesMargin4 = 17 + 4 * version + 16;
    console.log(
        `${label}: текст ${text.length} симв., JSON ${jsonLen}, deflate ${packedLen}B → ` +
        `QR v${version}, ${17 + 4 * version} модулей (+8 полей = ${modules}) (+16 полей = ${modulesMargin4})`
    );
}

report("Только адрес", camera({ name: "Двор", ipAddress: "192.168.1.64" }));

report("Типичная Hikvision", camera({
    name: "Двор", ipAddress: "192.168.1.64", onvifPort: 8000, rtspPath: "/Streaming/Channels/101",
    username: "admin", password: "Pass12345", protocol: "HIKVISION",
    knownPorts: [{ port: 554, kinds: ["RTSP"] }, { port: 8000, kinds: ["ONVIF", "HTTP"] }],
    hikvisionArchive: true, motionAlerts: true,
    streamProfiles: [
        { id: "", name: "Основной", rtspPath: "/Streaming/Channels/101" },
        { id: "", name: "Дополнительный", rtspPath: "/Streaming/Channels/102" },
    ],
}));

report("С пресетами и потоками", camera({
    name: "Двор «Север»", ipAddress: "192.168.1.64", onvifPort: 8000, rtspPath: "/Streaming/Channels/101",
    username: "admin", password: "Pass12345", protocol: "HIKVISION",
    knownPorts: [{ port: 554, kinds: ["RTSP"] }, { port: 8000, kinds: ["ONVIF", "HTTP"] }],
    hikvisionArchive: true, motionAlerts: true, showDateTime: false, clockPosition: "BOTTOM_START",
    presets: [
        { id: 1, name: "Ворота", token: "1", saved: true },
        { id: 2, name: "Калитка", token: "2", saved: true },
        { id: 3, name: "Парковка", token: "3", saved: true },
        ...Array.from({ length: 5 }, (_, i) => ({ id: i + 4, name: `Пресет ${i + 4}`, token: `${i + 4}`, saved: false })),
    ],
    streamProfiles: [
        { id: "", name: "Основной", rtspPath: "/Streaming/Channels/101" },
        { id: "", name: "Дополнительный", rtspPath: "/Streaming/Channels/102" },
        { id: "", name: "Третий", rtspPath: "/Streaming/Channels/103" },
    ],
}));

// Сколько пикселей экрана приходится на модуль при разной ширине кода
for (const px of [640, 760, 900]) {
    for (const modules of [61, 89, 105, 121]) {
        console.log(`код ${px}px, ${modules} модулей → ${(px / modules).toFixed(2)} px на модуль`);
    }
}
