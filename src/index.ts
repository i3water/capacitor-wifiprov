import { registerPlugin } from '@capacitor/core';

import type { WiFiProvPlugin } from './definitions';

const WiFiProv = registerPlugin<WiFiProvPlugin>('WiFiProv', {
  web: () => import('./web').then((module) => new module.WiFiProvWeb()),
});

export * from './definitions';
export { WiFiProv };
