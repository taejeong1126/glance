import { StyleSheet, Text, View } from 'react-native';
import QRCode from 'react-native-qrcode-svg';

function SetupScreen({ setupUrl }: { setupUrl: string }) {
    return (
        <View style={styles.container}>
            <View style={styles.qrFrame}>
                <QRCode value={setupUrl} size={88} backgroundColor="#ffffff" />
            </View>
            <Text style={styles.description}>휴대폰 카메라로 스캔하세요</Text>
        </View>
    );
}

const styles = StyleSheet.create({
    container: {
        flex: 1,
        alignItems: 'center',
        justifyContent: 'center',
        backgroundColor: '#050505',
        paddingHorizontal: 20
    },
    qrFrame: {
        alignItems: 'center',
        backgroundColor: '#ffffff',
        borderRadius: 8,
        height: 100,
        justifyContent: 'center',
        width: 100
    },
    description: {
        color: '#b8b8b8',
        fontSize: 11,
        marginTop: 14,
        textAlign: 'center'
    }
});

export default SetupScreen;
