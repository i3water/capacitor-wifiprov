import { registerPlugin } from '@capacitor/core';
const WiFiProv = registerPlugin('WiFiProv', {
    web: () => import('./web').then((module) => new module.WiFiProvWeb()),
});
export * from './definitions';
export { WiFiProv };
//# sourceMappingURL=index.js.map