import { AppRegistry } from 'react-native';
import { install as installQuickCrypto } from 'react-native-quick-crypto';
import App from './src/App';
import { name as appName } from './app.json';

installQuickCrypto();

AppRegistry.registerComponent(appName, () => App);
