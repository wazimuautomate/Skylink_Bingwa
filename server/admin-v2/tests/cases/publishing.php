<?php
/**
 * Publishing / change-detection cases.
 *
 * Pure logic only: no database, no HTTP. diffSnapshots() takes two plain arrays and must
 * stay that way, because the headline guarantee below — identical snapshots produce ZERO
 * diff items — is the thing that stops Preview claiming an offer changed when the operator
 * only opened it and pressed Save.
 *
 * Run with the rest of the suite: php tests/run.php
 */

/* ------------------------------------------------------ the empty-diff guarantee */

test('identical snapshots produce zero diff items', function () {
    eq(\App\Services\PublishingService::diffSnapshots(baseSnapshot(), baseSnapshot()), []);
});

test('re-saving without editing produces no diff (key order is irrelevant)', function () {
    $old = baseSnapshot();
    $new = baseSnapshot();
    // Same values, different key order — what a re-save through a form produces.
    $new['offers'][0] = array_reverse($new['offers'][0], true);
    eq(\App\Services\PublishingService::diffSnapshots($old, $new), []);
});

test('an empty captures object equals an empty captures array', function () {
    // The working snapshot uses stdClass so it serialises as {}; the published snapshot
    // decodes back to []. Those are the same value and must not look like an edit.
    eq(\App\Services\ChangeDetector::compareItems('offer', ['captures' => []], ['captures' => new stdClass()]), []);
});

/* --------------------------------------------------------------- single change */

test('one changed field yields exactly one item with exactly one field', function () {
    $old = baseSnapshot();
    $new = baseSnapshot();
    $new['offers'][0]['price'] = 25;

    $items = \App\Services\PublishingService::diffSnapshots($old, $new);
    eq(count($items), 1);
    eq($items[0]['change_type'], 'changed');
    eq($items[0]['module'], 'offers');
    eq($items[0]['entity_type'], 'offer');
    eq($items[0]['entity_id'], 'data_1');
    eq(count($items[0]['fields']), 1);
    eq($items[0]['fields'][0]['field'], 'price');
    eq($items[0]['fields'][0]['label'], 'Price');
    eq($items[0]['fields'][0]['from'], 19);
    eq($items[0]['fields'][0]['to'], 25);
    eq($items[0]['summary'], 'Price updated');
});

/* -------------------------------------------------- added / removed per section */

test('removed category is detected in the categories module', function () {
    $old = baseSnapshot();
    $old['categories'][] = ['id' => 'DATA', 'label' => 'Data', 'sortOrder' => 10];
    $new = baseSnapshot();

    $items = \App\Services\PublishingService::diffSnapshots($old, $new);
    eq(count($items), 1);
    eq($items[0]['change_type'], 'removed');
    eq($items[0]['module'], 'categories');
    eq($items[0]['entity_id'], 'DATA');
    eq($items[0]['summary'], 'Removed Data');
});

test('a changed billboard lands in its own module', function () {
    $old = baseSnapshot();
    $old['billboards'][] = ['id' => 7, 'headline' => 'Weekend deal', 'priority' => 5];
    $new = $old;
    $new['billboards'][0]['priority'] = 1;

    $items = \App\Services\PublishingService::diffSnapshots($old, $new);
    $modules = array_column($items, 'module');
    eq($modules, ['billboards']);

    $grouped = \App\Services\PublishingService::groupByModule($items);
    eq(array_keys($grouped), ['billboards']); // MODULES order, not insertion order
    eq($grouped['billboards']['count'], 1);
    eq($grouped['billboards']['label'], 'Billboards');
});

test('singleton sections diff their own keys', function () {
    $old = baseSnapshot();
    $new = baseSnapshot();
    $new['support']['tillNumber'] = '999999';
    $new['featureFlags'] = ['offline_purchase' => false];
    $old['featureFlags'] = ['offline_purchase' => true];

    $items = \App\Services\PublishingService::diffSnapshots($old, $new);
    eq(count($items), 2);

    $byType = [];
    foreach ($items as $it) { $byType[$it['entity_type']] = $it; }

    ok(isset($byType['support']), 'support change missing');
    eq($byType['support']['module'], 'support');
    eq($byType['support']['entity_id'], 'support');
    eq(count($byType['support']['fields']), 1);
    eq($byType['support']['fields'][0]['field'], 'tillNumber');
    eq($byType['support']['fields'][0]['label'], 'Till number');

    ok(isset($byType['featureFlags']), 'feature flag change missing');
    eq($byType['featureFlags']['fields'][0]['field'], 'offline_purchase');
    eq($byType['featureFlags']['fields'][0]['from'], true);
    eq($byType['featureFlags']['fields'][0]['to'], false);
});

/* --------------------------------------------------------- ChangeDetector detail */

test('compareItems flattens nested keys and compares lists by index', function () {
    $old = ['variations' => [['title' => 'A', 'body' => 'One'], ['title' => 'B', 'body' => 'Two']]];
    $new = ['variations' => [['title' => 'A', 'body' => 'One'], ['title' => 'B', 'body' => 'Three']]];

    $fields = \App\Services\ChangeDetector::compareItems('notification', $old, $new);
    eq(count($fields), 1);
    eq($fields[0]['field'], 'variations.1.body');
    eq($fields[0]['from'], 'Two');
    eq($fields[0]['to'], 'Three');
    ok(strpos($fields[0]['label'], '#2') !== false, 'index should read as #2');
});

test('compareItems reports a longer list by index, not as one blob', function () {
    $fields = \App\Services\ChangeDetector::compareItems('notification', ['daysOfWeek' => [1, 2]], ['daysOfWeek' => [1, 3]]);
    eq(count($fields), 1);
    eq($fields[0]['field'], 'daysOfWeek.1');
    eq($fields[0]['to'], 3);
});

test('fieldLabel humanises unknown fields and uses the map for known ones', function () {
    eq(\App\Services\ChangeDetector::fieldLabel('offer', 'price'), 'Price');
    eq(\App\Services\ChangeDetector::fieldLabel('offer', 'dailyRule'), 'Daily limit');
    eq(\App\Services\ChangeDetector::fieldLabel('offer', 'somethingNew'), 'Something new');
});

test('summarise reads as a sentence', function () {
    $fields = [
        ['field' => 'price', 'label' => 'Price', 'from' => 19, 'to' => 25],
        ['field' => 'priority', 'label' => 'Priority', 'from' => 1, 'to' => 2],
    ];
    eq(\App\Services\ChangeDetector::summarise('changed', '1GB', $fields), 'Price updated, Priority updated');
    eq(\App\Services\ChangeDetector::summarise('added', '1GB', []), 'Added 1GB');
    eq(\App\Services\ChangeDetector::summarise('removed', '1GB', []), 'Removed 1GB');
    eq(\App\Services\ChangeDetector::summarise('changed', '1GB', []), '1GB changed');
});

test('displayValue is readable for every value kind', function () {
    eq(\App\Services\ChangeDetector::displayValue(null), '(none)');
    eq(\App\Services\ChangeDetector::displayValue(true), 'Yes');
    eq(\App\Services\ChangeDetector::displayValue(false), 'No');
    eq(\App\Services\ChangeDetector::displayValue(''), '(empty)');
    eq(\App\Services\ChangeDetector::displayValue([]), '(empty)');
    eq(\App\Services\ChangeDetector::displayValue('Bingwa'), 'Bingwa');
    eq(\App\Services\ChangeDetector::displayValue(19, 'price'), 'KSh 19');
    eq(\App\Services\ChangeDetector::displayValue(19), '19');
    eq(\App\Services\ChangeDetector::displayValue(['a' => 1]), '{"a":1}');
});

/* ------------------------------------------------------------ resource versions */

test('resource versions keep an unchanged section and bump a changed one', function () {
    $snap = baseSnapshot();
    $first = \App\Services\ResourceVersions::compute($snap, [], 5);
    eq($first['offers']['version'], 5);
    eq($first['support']['version'], 5);
    eq($first['offers']['changed'], true);

    $edited = $snap;
    $edited['offers'][0]['price'] = 25;
    $second = \App\Services\ResourceVersions::compute($edited, $first, 6);

    eq($second['offers']['version'], 6);
    eq($second['offers']['changed'], true);
    eq($second['support']['version'], 5);
    eq($second['support']['changed'], false);
});

test('an untouched snapshot moves no resource version at all', function () {
    $snap = baseSnapshot();
    $first = \App\Services\ResourceVersions::compute($snap, [], 5);
    $second = \App\Services\ResourceVersions::compute($snap, $first, 6);
    foreach ($second as $key => $row) {
        eq($row['version'], 5, "resource {$key} should not have moved");
        eq($row['changed'], false, "resource {$key} should not be marked changed");
    }
});

/* ---------------------------------------------------------------------------
 * Publish -> re-read -> diff must be EMPTY.
 *
 * This is the regression guard for the bug the owner reported as "Preview shows
 * 41 pending changes and I changed nothing". A publish stores the snapshot as
 * canonical JSON and every later comparison decodes it again. If ANY value
 * survives that round trip in a different shape - an empty map becoming an empty
 * list, an int arriving as a string, a bool as 0/1 - the diff would report a
 * change for ever and pressing Publish would never clear it.
 * ------------------------------------------------------------------------- */

/** A snapshot shaped exactly like PublishingService::buildWorkingSnapshot() output. */
function richWorkingSnapshot(): array
{
    return [
        'schemaVersion' => 1,
        'offers' => [[
            'id' => 'data_6', 'category' => 'DATA', 'name' => '2GB', 'price' => 110,
            'validity' => '24 Hrs', 'band' => 'Daily', 'dailyRule' => 'MULTIPLE_PER_DAY',
            'policy' => 'MULTIPLE_PER_DAY', 'maxPerDay' => null, 'commercialTag' => '',
            'offlineEligible' => true, 'restrictions' => '',
        ]],
        'categories' => [[
            'id' => 'DATA', 'label' => 'Data', 'description' => 'Data bundles.',
            'accent' => 'info', 'sortOrder' => 10,
        ]],
        'billboards' => [[
            'id' => 3, 'kind' => 'offer', 'priority' => 5, 'displayOrder' => 0,
            'linkedOfferId' => 'data_6', 'tag' => 'BEST VALUE', 'headline' => '2GB for KSh 110',
            'body' => 'Stay connected.', 'ctaLabel' => 'Buy now', 'ctaDestination' => '',
            'mediaType' => 'none', 'imageUrl' => '', 'thumbUrl' => '', 'altText' => '',
            'targetAction' => 'offer', 'clickUrl' => '', 'internalAction' => '',
            'targetCategory' => '', 'audienceRule' => 'all', 'frequencyCap' => 0,
            'startsAt' => null, 'endsAt' => null,
        ]],
        'notifications' => [[
            'id' => 1, 'name' => 'Offline nudge', 'category' => 'OFFLINE', 'trigger' => 'offline',
            'triggerEvent' => '', 'priority' => 'normal',
            'variations' => [['title' => 'Offline', 'body' => 'Looks like you are offline.']],
            'deepLink' => '', 'linkedOfferId' => null, 'startsOn' => null, 'endsOn' => null,
            'daysOfWeek' => [], 'timeStart' => '', 'timeEnd' => '', 'cooldownMinutes' => 720,
            'frequencyCap' => 1, 'respectQuietHours' => true, 'suppressRecentPurchase' => true,
            'expiresAt' => null,
        ]],
        'support' => ['tillNumber' => '4063396', 'paybillNumber' => '', 'supportNumber' => '',
                      'supportWhatsapp' => '', 'offlineSelfInstructions' => 'Buy Goods.',
                      'offlineOtherInstructions' => 'Pay Bill.', 'supportBanner' => '', 'workingHours' => ''],
        'appConfig' => ['maintenanceMode' => false, 'maintenanceMessage' => '',
                        'syncIntervalMinutes' => 360, 'generalSupportMessage' => ''],
        'featureFlags' => ['offline_purchase' => true, 'billboards' => true],
        'version' => ['latestVersionCode' => 3, 'latestVersionName' => '1.0.2',
                      'minSupportedVersionCode' => 1, 'mandatory' => false, 'updateSource' => 'github',
                      'playStoreUrl' => '', 'apkUrl' => 'https://x/y.apk', 'apkSha256' => '',
                      'rolloutPercent' => 100, 'releaseNotes' => ''],
    ];
}

test('a published snapshot re-read from JSON diffs clean against itself', function () {
    $working = richWorkingSnapshot();

    // Exactly what publish() stores...
    $stored = \App\Core\Snapshot::canonical($working);
    // ...and exactly how currentSnapshot() reads it back.
    $published = json_decode($stored, true);
    ok(is_array($published), 'stored snapshot must decode to an array');

    $items = \App\Services\PublishingService::diffSnapshots($published, $working);
    if ($items !== []) {
        $names = [];
        foreach ($items as $it) {
            $fields = [];
            foreach (($it['fields'] ?? []) as $f) {
                $fields[] = (string) ($f['field'] ?? '?');
            }
            $names[] = $it['module'] . '/' . $it['entity_id'] . ' [' . implode(',', $fields) . ']';
        }
        throw new Exception('publish would never clear; still pending: ' . implode('; ', $names));
    }
    eq($items, []);
});

test('re-publishing an unchanged snapshot moves no resource version', function () {
    $working = richWorkingSnapshot();
    $published = json_decode(\App\Core\Snapshot::canonical($working), true);
    $first = \App\Services\ResourceVersions::compute($published, [], 9);
    $second = \App\Services\ResourceVersions::compute($working, $first, 10);
    foreach ($second as $key => $row) {
        eq($row['changed'], false, "resource {$key} changed across a publish round trip");
        eq($row['version'], 9, "resource {$key} version moved without content changing");
    }
});

test('a genuinely edited price is still detected after a round trip', function () {
    $working = richWorkingSnapshot();
    $published = json_decode(\App\Core\Snapshot::canonical($working), true);
    $working['offers'][0]['price'] = 120;
    $items = \App\Services\PublishingService::diffSnapshots($published, $working);
    eq(count($items), 1, 'exactly one entity should be reported');
    eq($items[0]['module'], 'offers');
    eq(count($items[0]['fields']), 1, 'exactly one field should be reported');
    eq($items[0]['fields'][0]['field'], 'price');
});

test('an empty map hashes the same whether it is an object or an array', function () {
    // The exact production hazard: the working snapshot carries stdClass so the published
    // JSON contains {}, but a decoded snapshot yields []. If those hashed differently the
    // resource version would rise on every publish and every device would re-download.
    $asObject = ['notifications' => [['id' => 'r', 'captures' => new \stdClass()]]];
    $asArray  = ['notifications' => [['id' => 'r', 'captures' => []]]];
    eq(
        \App\Services\ResourceVersions::checksums($asObject)['notifications'],
        \App\Services\ResourceVersions::checksums($asArray)['notifications']
    );
});
