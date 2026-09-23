import {formatText, getLanguage, getPluginI18n, getRes, normalizeLang, setLanguage} from './plugin';

const leaves = (resource, prefix = '') => Object.entries(resource).flatMap(([key, value]) =>
    typeof value === 'string' ? [[`${prefix}${key}`, value]] : leaves(value, `${prefix}${key}.`));

afterEach(() => setLanguage('zh_CN'));

test('both languages contain the same messages and interpolation parameters', () => {
    const chinese = new Map(leaves(getPluginI18n('zh_CN')));
    const english = new Map(leaves(getPluginI18n('en_US')));
    expect([...english.keys()]).toEqual([...chinese.keys()]);
    chinese.forEach((value, key) => {
        expect(english.get(key).trim()).not.toBe('');
        expect((english.get(key).match(/\{\w+\}/g) || []).sort())
            .toEqual((value.match(/\{\w+\}/g) || []).sort());
    });
});

test('language changes use the configured resources, with Chinese as the fallback', () => {
    setLanguage('en-US');
    expect(getLanguage()).toBe('en_US');
    expect(getRes().runtime.scheduler).toBe('Scheduler');
    setLanguage('zh_CN');
    expect(getRes().runtime.scheduler).toBe('调度中心');
    expect(normalizeLang('en-GB')).toBe('en_US');
    expect(normalizeLang('fr_FR')).toBe('zh_CN');
    expect(normalizeLang()).toBe('zh_CN');
});

test('interpolation preserves user text and zero counts', () => {
    setLanguage('en_US');
    expect(formatText(getRes().plugins.count, {visible: 0, total: 5})).toBe('Showing 0 / 5 plugins');
    expect(formatText(getRes().plugins.uninstallLabel, {name: '$& <plugin>'})).toBe('Uninstall $& <plugin>');
});
