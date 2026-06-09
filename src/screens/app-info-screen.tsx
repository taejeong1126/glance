import { useEffect } from 'react';
import { BackHandler, Image, Linking, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';

type Props = {
    onBack: () => void;
};

const EMAIL = 'taejeong654@gmail.com';
const GITHUB_URL = 'https://github.com/taejeong-labs/glance';

export default function AppInfoScreen({ onBack }: Props) {
    useEffect(() => {
        const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
            onBack();
            return true;
        });

        return () => subscription.remove();
    }, [onBack]);

    return (
        <ScrollView contentContainerStyle={styles.container} showsVerticalScrollIndicator={false}>
            <View style={styles.profile}>
                <Image source={require('../assets/developer-profile.png')} style={styles.avatar} />
                <Text style={styles.name}>김태정</Text>
            </View>

            <Pressable style={styles.infoLink} onPress={() => Linking.openURL(`mailto:${EMAIL}`)}>
                <Text style={styles.infoLabel}>이메일</Text>
                <Text style={styles.infoValue}>{EMAIL}</Text>
            </Pressable>
            <Pressable style={styles.infoLink} onPress={() => Linking.openURL(GITHUB_URL)}>
                <Text style={styles.infoLabel}>GitHub</Text>
                <Text style={styles.infoValue}>https://github.com/taejeong-labs/glance</Text>
            </Pressable>
            <Pressable accessibilityLabel="뒤로 가기" style={styles.backButton} onPress={onBack}>
                <Text style={styles.backText}>뒤로 가기</Text>
            </Pressable>
        </ScrollView>
    );
}

const styles = StyleSheet.create({
    container: {
        alignItems: 'center',
        backgroundColor: '#050505',
        flexGrow: 1,
        minHeight: '100%',
        paddingBottom: 32,
        paddingHorizontal: 28,
        paddingTop: 14
    },
    backButton: {
        alignItems: 'center',
        backgroundColor: '#27272a',
        borderRadius: 20,
        height: 40,
        justifyContent: 'center',
        marginTop: 10,
        width: '100%'
    },
    backText: {
        color: '#ffffff',
        fontSize: 11,
        fontWeight: '600'
    },
    title: {
        color: '#ffffff',
        fontSize: 17,
        fontWeight: '700',
        marginTop: 8,
        textAlign: 'center'
    },
    profile: {
        alignItems: 'center',
        marginBottom: 5,
        marginTop: 12
    },
    avatar: {
        borderRadius: 28,
        borderColor: '#3f3f46',
        borderWidth: 1,
        height: 56,
        resizeMode: 'cover',
        width: 56
    },
    name: {
        color: '#ffffff',
        fontSize: 18,
        fontWeight: '700',
        marginTop: 9
    },

    infoLink: {
        alignItems: 'center',
        marginBottom: 10,
        width: '100%'
    },
    infoLabel: {
        color: '#a1a1aa',
        fontSize: 9,
        marginBottom: 3,
        textAlign: 'center'
    },
    infoValue: {
        color: '#ffffff',
        fontSize: 11,
        fontWeight: '500',
        textAlign: 'center'
    }
});
