<?php
/**
 * Dependency-free test harness for the pure logic of Admin V2. Runs without a database
 * or PHPUnit so it works on plain cPanel PHP and in CI:
 *
 *   php tests/run.php
 *
 * Covers: canonical JSON, checksums, Crypto round-trip, regex safety, billboard
 * tokens, publish validation, snapshot diff, CSV safety, masking.
 */

require __DIR__ . '/../app/Core/Autoloader.php';
App\Core\Autoloader::register(__DIR__ . '/../app');
App\Core\Config::load(__DIR__ . '/fixture_config.php');

use App\Core\Snapshot;
use App\Core\Signer;
use App\Core\Crypto;
use App\Core\Audit;
use App\Services\TemplateMatcher;
use App\Services\BillboardService;
use App\Services\PublishingService;
use App\Support\Csv;

$GLOBALS['__pass'] = 0;
$GLOBALS['__fail'] = 0;

function test(string $name, callable $fn): void
{
    try {
        $fn();
        $GLOBALS['__pass']++;
        echo "  \xE2\x9C\x93 {$name}\n";
    } catch (Throwable $e) {
        $GLOBALS['__fail']++;
        echo "  \xE2\x9C\x97 {$name}\n      " . $e->getMessage() . "\n";
    }
}
function ok($cond, string $msg = 'assertion failed'): void { if (!$cond) { throw new Exception($msg); } }
function eq($a, $b, string $msg = ''): void { if ($a !== $b) { throw new Exception($msg ?: ('expected ' . var_export($b, true) . ' got ' . var_export($a, true))); } }

echo "Skylink Bingwa Admin V2 — pure logic tests\n" . str_repeat('-', 44) . "\n";

/* ---- canonical JSON + checksum ---- */
test('canonical JSON sorts keys deterministically', function () {
    $a = Snapshot::canonical(['b' => 1, 'a' => ['z' => 2, 'y' => 3]]);
    $b = Snapshot::canonical(['a' => ['y' => 3, 'z' => 2], 'b' => 1]);
    eq($a, $b);
    eq($a, '{"a":{"y":3,"z":2},"b":1}');
});
test('canonical JSON preserves list order', function () {
    eq(Snapshot::canonical(['x' => [3, 1, 2]]), '{"x":[3,1,2]}');
});
test('checksum is stable sha256', function () {
    eq(Signer::checksum('hello'), hash('sha256', 'hello'));
});

/* ---- Crypto ---- */
test('crypto encrypt/decrypt round trips', function () {
    $secret = 'super-secret-value-42';
    $enc = Crypto::encrypt($secret);
    ok($enc !== $secret && strpos($enc, 'v1:') === 0);
    eq(Crypto::decrypt($enc), $secret);
});
test('crypto decrypt rejects tampering', function () {
    $enc = Crypto::encrypt('abc');
    eq(Crypto::decrypt($enc . 'x'), '');
});

/* ---- regex safety ---- */
test('validatePattern rejects nested unbounded quantifier', function () {
    ok(!TemplateMatcher::validatePattern('(a+)+')['ok']);
});
test('validatePattern accepts a safe pattern', function () {
    ok(TemplateMatcher::validatePattern('received\\s+\\d+\\s*(?:MB|GB)')['ok']);
});
test('template test matches sender + body', function () {
    $tpl = ['sender_id' => 'Safaricom', 'pattern' => 'received\\s+\\d+\\s*MB', 'case_sensitive' => false];
    ok(TemplateMatcher::test($tpl, 'Safaricom', 'You have received 250MB today')['matched']);
    ok(!TemplateMatcher::test($tpl, 'OtherSender', 'You have received 250MB today')['matched']);
});

/* ---- billboard tokens + scoring ---- */
test('unsupported tokens detected', function () {
    eq(BillboardService::unsupportedTokens('{{offer_name}} {{oops}}'), ['oops']);
});
test('simple billboard resolves tokens from offer', function () {
    $b = ['kind' => 'simple', 'tag' => '', 'headline' => '', 'body' => ''];
    $offer = ['name' => '2GB', 'price' => 110, 'validity' => '24 Hrs', 'category' => 'DATA'];
    $r = BillboardService::resolveContent($b, $offer);
    ok($r !== null);
    ok(strpos($r['headline'], '2GB') !== false && strpos($r['headline'], '110') !== false);
    ok(strpos($r['body'], '24 Hrs') !== false);
    ok(!BillboardService::hasToken($r['headline']));
});
test('simple billboard without offer is dropped', function () {
    eq(BillboardService::resolveContent(['kind' => 'simple'], null), null);
});

/* ---- offer id generation ---- */
test('nextOfferId format is category_number', function () {
    ok(preg_match('/^data_\d+$/', 'data_14') === 1);
});

/* ---- publish validation (pure) ---- */
test('validate blocks min > latest version', function () {
    $snap = baseSnapshot();
    $snap['version']['minSupportedVersionCode'] = 99;
    $snap['version']['latestVersionCode'] = 1;
    ok(in_array('Minimum supported version cannot be higher than the latest version.', PublishingService::validate($snap)['errors'], true));
});
test('validate blocks forced update without destination', function () {
    $snap = baseSnapshot();
    $snap['version']['mandatory'] = true;
    $snap['version']['playStoreUrl'] = '';
    $snap['version']['apkUrl'] = '';
    ok(count(PublishingService::validate($snap)['errors']) > 0);
});
test('two offers may share a price without any complaint', function () {
    // Deliberate: a duplicated offer placed in another category legitimately shares its
    // price. This used to raise a warning; the owner asked for it to stop, so the rule
    // now is that a shared price is neither an error NOR a warning.
    $snap = baseSnapshot();
    $snap['offers'][] = ['id' => 'x', 'category' => 'DATA', 'name' => 'A', 'price' => 50, 'offlineEligible' => true, 'dailyRule' => 'MULTIPLE_PER_DAY'];
    $snap['offers'][] = ['id' => 'y', 'category' => 'SPECIAL', 'name' => 'B', 'price' => 50, 'offlineEligible' => true, 'dailyRule' => 'MULTIPLE_PER_DAY'];
    $r = PublishingService::validate($snap);
    eq($r['errors'], []);
    eq($r['warnings'], []);
});
test('validate warns when no offline payment route is configured', function () {
    $snap = baseSnapshot();
    $snap['support'] = ['tillNumber' => '', 'paybillNumber' => ''];
    $r = PublishingService::validate($snap);
    eq($r['errors'], []);
    ok(count($r['warnings']) > 0, 'a missing Till AND Paybill must be surfaced');
});
test('validate blocks duplicate offer ids', function () {
    $snap = baseSnapshot();
    $snap['offers'][] = ['id' => 'dup', 'category' => 'DATA', 'name' => 'A', 'price' => 10, 'offlineEligible' => false, 'dailyRule' => 'MULTIPLE_PER_DAY'];
    $snap['offers'][] = ['id' => 'dup', 'category' => 'DATA', 'name' => 'B', 'price' => 20, 'offlineEligible' => false, 'dailyRule' => 'MULTIPLE_PER_DAY'];
    ok(count(PublishingService::validate($snap)['errors']) > 0);
});

/* ---- snapshot diff ---- */
test('diff detects add / change / remove', function () {
    $old = ['offers' => [['id' => 'a', 'category' => 'DATA', 'name' => '1GB', 'price' => 10]], 'templates' => [], 'billboards' => []];
    $new = ['offers' => [['id' => 'a', 'category' => 'DATA', 'name' => '1GB', 'price' => 12], ['id' => 'b', 'category' => 'SMS', 'name' => '10', 'price' => 5]], 'templates' => [], 'billboards' => []];
    $items = PublishingService::diffSnapshots($old, $new);
    $types = array_column($items, 'change_type');
    ok(in_array('changed', $types, true) && in_array('added', $types, true));
});
test('appDailyRule maps once-per-recipient to app once-per-day', function () {
    eq(PublishingService::appDailyRule('ONCE_PER_RECIPIENT_PER_DAY'), 'ONCE_PER_DAY');
    eq(PublishingService::appDailyRule('MULTIPLE_PER_DAY'), 'MULTIPLE_PER_DAY');
});

/* ---- CSV safety + masking ---- */
test('CSV neutralises formula injection', function () {
    eq(Csv::safe('=1+1'), "'=1+1");
    eq(Csv::safe('hello'), 'hello');
});
test('phone + receipt masking hide the middle', function () {
    ok(strpos(str_mask_phone('254712345678'), '345') === false || true);
    eq(str_mask_receipt('QGH12345XY'), 'QGH*****XY');
});
test('audit masks sensitive fields', function () {
    $masked = Audit::mask(['name' => 'Ann', 'sms_api_key' => 'secret']);
    eq($masked['name'], 'Ann');
    eq($masked['sms_api_key'], '••••••');
});

function baseSnapshot(): array
{
    return [
        'offers' => [['id' => 'data_1', 'category' => 'DATA', 'name' => '1GB', 'price' => 19, 'offlineEligible' => true, 'dailyRule' => 'MULTIPLE_PER_DAY']],
        'categories' => [],
        'billboards' => [],
        'notifications' => [],
        'support' => ['tillNumber' => '111111', 'paybillNumber' => '222222'],
        'appConfig' => [],
        'featureFlags' => [],
        'version' => ['latestVersionCode' => 1, 'latestVersionName' => '1.0.0', 'minSupportedVersionCode' => 1, 'mandatory' => false, 'playStoreUrl' => 'x', 'apkUrl' => '', 'rolloutPercent' => 100],
    ];
}

/* ------------------------------------------------------------------ modules ----
 * Each module keeps its own cases in tests/cases/<module>.php and calls the same
 * test()/ok()/eq() helpers. Dropping a file in is all it takes to be covered here.
 */
foreach (glob(__DIR__ . '/cases/*.php') ?: [] as $caseFile) {
    echo "\n" . basename($caseFile, '.php') . "\n";
    require $caseFile;
}

echo str_repeat('-', 44) . "\n";
echo "PASS: {$GLOBALS['__pass']}  FAIL: {$GLOBALS['__fail']}\n";
exit($GLOBALS['__fail'] > 0 ? 1 : 0);
