<?php
/**
 * Billboard pure-logic cases: the effective (date-derived) state, tap-target validation,
 * URL safety and the image helpers. No database, no filesystem, no GD — everything here
 * must be decidable from its arguments alone.
 *
 * Required by tests/run.php, which already defined test(), ok() and eq().
 */

/* --------------------------------------------------------- effective state ---- */

$bbNow = new DateTimeImmutable('2026-07-31 12:00:00', new DateTimeZone('Africa/Nairobi'));

test('effectiveState: an advert switched off is off whatever its dates say', function () use ($bbNow) {
    $b = ['enabled' => 0, 'status' => 'active', 'kind' => 'advanced', 'starts_at' => null, 'ends_at' => null];
    eq(\App\Services\BillboardService::effectiveState($b, $bbNow), 'disabled');
});
test('effectiveState: draft status reads as draft', function () use ($bbNow) {
    $b = ['enabled' => 1, 'status' => 'draft', 'kind' => 'advanced'];
    eq(\App\Services\BillboardService::effectiveState($b, $bbNow), 'draft');
});
test('effectiveState: paused and archived are off', function () use ($bbNow) {
    eq(\App\Services\BillboardService::effectiveState(['enabled' => 1, 'status' => 'paused', 'kind' => 'simple'], $bbNow), 'disabled');
    eq(\App\Services\BillboardService::effectiveState(['enabled' => 1, 'status' => 'archived', 'kind' => 'advanced'], $bbNow), 'disabled');
});
test('effectiveState: a simple advert is always-on and ignores its window', function () use ($bbNow) {
    // Deliberate: PublishingService::buildBillboards() publishes simple billboards with no
    // schedule, so an expired-looking window must not read as ended here either.
    $b = ['enabled' => 1, 'status' => 'active', 'kind' => 'simple',
          'starts_at' => '2020-01-01 00:00:00', 'ends_at' => '2020-02-01 00:00:00'];
    eq(\App\Services\BillboardService::effectiveState($b, $bbNow), 'live');
});
test('effectiveState: an advanced advert starting later is scheduled', function () use ($bbNow) {
    $b = ['enabled' => 1, 'status' => 'scheduled', 'kind' => 'advanced',
          'starts_at' => '2026-08-05 06:00:00', 'ends_at' => null];
    eq(\App\Services\BillboardService::effectiveState($b, $bbNow), 'scheduled');
});
test('effectiveState: an advanced advert whose window closed is ended', function () use ($bbNow) {
    $b = ['enabled' => 1, 'status' => 'active', 'kind' => 'advanced',
          'starts_at' => '2026-07-01 06:00:00', 'ends_at' => '2026-07-20 06:00:00'];
    eq(\App\Services\BillboardService::effectiveState($b, $bbNow), 'ended');
});
test('effectiveState: an advanced advert inside its window is live without being toggled', function () use ($bbNow) {
    $b = ['enabled' => 1, 'status' => 'scheduled', 'kind' => 'advanced',
          'starts_at' => '2026-07-30 06:00:00', 'ends_at' => '2026-08-30 06:00:00'];
    eq(\App\Services\BillboardService::effectiveState($b, $bbNow), 'live');
});
test('effectiveState: an advanced advert with no dates is live', function () use ($bbNow) {
    $b = ['enabled' => 1, 'status' => 'active', 'kind' => 'advanced', 'starts_at' => '', 'ends_at' => null];
    eq(\App\Services\BillboardService::effectiveState($b, $bbNow), 'live');
});
test('effectiveState: stored dates are read as UTC, not as local time', function () {
    // 05:30 UTC is 08:30 in Nairobi, so at 08:00 Nairobi the advert has NOT started yet.
    $now = new DateTimeImmutable('2026-07-31 08:00:00', new DateTimeZone('Africa/Nairobi'));
    $b = ['enabled' => 1, 'status' => 'scheduled', 'kind' => 'advanced',
          'starts_at' => '2026-07-31 05:30:00', 'ends_at' => null];
    eq(\App\Services\BillboardService::effectiveState($b, $now), 'scheduled');
});
test('effectiveState labels and status classes stay inside the shared palette', function () {
    eq(\App\Services\BillboardService::stateLabel('live'), 'Live now');
    eq(\App\Services\BillboardService::stateClass('ended'), 'expired');
    eq(\App\Services\BillboardService::stateClass('disabled'), 'paused');
});

/* ------------------------------------------------------------ tap target ------ */

$bbCats = ['DATA', 'SMS', 'MINUTES', 'SPECIAL'];

test('validateTarget: none needs nothing', function () use ($bbCats) {
    eq(\App\Services\BillboardService::validateTarget(['target_action' => 'none'], $bbCats, false), []);
});
test('validateTarget: an unknown action is refused', function () use ($bbCats) {
    eq(count(\App\Services\BillboardService::validateTarget(['target_action' => 'wormhole'], $bbCats, true)), 1);
});
test('validateTarget: offer needs a linked offer that exists', function () use ($bbCats) {
    eq(count(\App\Services\BillboardService::validateTarget(['target_action' => 'offer', 'linked_offer_id' => ''], $bbCats, false)), 1);
    eq(count(\App\Services\BillboardService::validateTarget(['target_action' => 'offer', 'linked_offer_id' => 'gone_9'], $bbCats, false)), 1);
    eq(\App\Services\BillboardService::validateTarget(['target_action' => 'offer', 'linked_offer_id' => 'data_6'], $bbCats, true), []);
});
test('validateTarget: category must be one of the enabled categories', function () use ($bbCats) {
    eq(count(\App\Services\BillboardService::validateTarget(['target_action' => 'category', 'target_category' => ''], $bbCats, false)), 1);
    eq(count(\App\Services\BillboardService::validateTarget(['target_action' => 'category', 'target_category' => 'ROAMING'], $bbCats, false)), 1);
    eq(\App\Services\BillboardService::validateTarget(['target_action' => 'category', 'target_category' => 'SMS'], $bbCats, false), []);
});
test('validateTarget: category list is data, not a hardcoded four', function () {
    // A deployment that renamed/added categories must be honoured.
    eq(\App\Services\BillboardService::validateTarget(['target_action' => 'category', 'target_category' => 'ROAMING'], ['ROAMING'], false), []);
    eq(count(\App\Services\BillboardService::validateTarget(['target_action' => 'category', 'target_category' => 'DATA'], ['ROAMING'], false)), 1);
});
test('validateTarget: url must be an https address', function () use ($bbCats) {
    eq(count(\App\Services\BillboardService::validateTarget(['target_action' => 'url', 'click_url' => 'http://example.co.ke'], $bbCats, false)), 1);
    eq(count(\App\Services\BillboardService::validateTarget(['target_action' => 'url', 'click_url' => ''], $bbCats, false)), 1);
    eq(\App\Services\BillboardService::validateTarget(['target_action' => 'url', 'click_url' => 'https://example.co.ke/promo'], $bbCats, false), []);
});
test('validateTarget: internal needs a screen name', function () use ($bbCats) {
    eq(count(\App\Services\BillboardService::validateTarget(['target_action' => 'internal', 'internal_action' => '   '], $bbCats, false)), 1);
    eq(\App\Services\BillboardService::validateTarget(['target_action' => 'internal', 'internal_action' => 'favourites'], $bbCats, false), []);
});

/* ------------------------------------------------------------- url safety ----- */

test('isAllowedUrl accepts a normal https address', function () {
    ok(\App\Services\BillboardService::isAllowedUrl('https://skylinkbingwa.co.ke/offers'));
    ok(\App\Services\BillboardService::isAllowedUrl('https://sub.example.com/a/b?c=1&d=2'));
});
test('isAllowedUrl rejects http, javascript and data', function () {
    ok(!\App\Services\BillboardService::isAllowedUrl('http://example.com'));
    ok(!\App\Services\BillboardService::isAllowedUrl('javascript:alert(1)'));
    ok(!\App\Services\BillboardService::isAllowedUrl('JavaScript:alert(1)'));
    ok(!\App\Services\BillboardService::isAllowedUrl('data:text/html;base64,PHN2Zz4='));
    ok(!\App\Services\BillboardService::isAllowedUrl('//example.com/x'));
});
test('isAllowedUrl rejects rubbish, empty and hostless input', function () {
    ok(!\App\Services\BillboardService::isAllowedUrl(''));
    ok(!\App\Services\BillboardService::isAllowedUrl('   '));
    ok(!\App\Services\BillboardService::isAllowedUrl('not a url'));
    ok(!\App\Services\BillboardService::isAllowedUrl('https://'));
    ok(!\App\Services\BillboardService::isAllowedUrl('https:///nohost'));
    ok(!\App\Services\BillboardService::isAllowedUrl("https://exa mple.com"));
    ok(!\App\Services\BillboardService::isAllowedUrl("https://exam\nple.com/x"));
    ok(!\App\Services\BillboardService::isAllowedUrl("https://example.com/\tx"));
    ok(!\App\Services\BillboardService::isAllowedUrl('https://' . str_repeat('a', 260) . '.com'));
});

/* ------------------------------------------------------- image pure helpers --- */

test('isAnimatedGifBytes finds two or more frame separators', function () {
    $sep = "\x00\x21\xF9\x04";
    $animated = 'GIF89a' . $sep . 'frame-one' . $sep . 'frame-two';
    ok(\App\Services\ImageUploader::isAnimatedGifBytes($animated));
    eq(\App\Services\ImageUploader::gifFrameCount($animated), 2);
});
test('isAnimatedGifBytes says no for a single-frame or separator-free GIF', function () {
    $sep = "\x00\x21\xF9\x04";
    ok(!\App\Services\ImageUploader::isAnimatedGifBytes('GIF89a' . $sep . 'only-frame'));
    ok(!\App\Services\ImageUploader::isAnimatedGifBytes('GIF89a-no-separators-here'));
    eq(\App\Services\ImageUploader::gifFrameCount('GIF89a-no-separators-here'), 1);
});
test('targetDimensions scales down and keeps the aspect ratio', function () {
    eq(\App\Services\ImageUploader::targetDimensions(2880, 1440, 1440), [1440, 720]);
    eq(\App\Services\ImageUploader::targetDimensions(2000, 1000, 400), [400, 200]);
});
test('targetDimensions never upscales a small picture', function () {
    eq(\App\Services\ImageUploader::targetDimensions(600, 400, 1440), [600, 400]);
    eq(\App\Services\ImageUploader::targetDimensions(1440, 900, 1440), [1440, 900]);
});
test('targetDimensions is safe for nonsense input', function () {
    eq(\App\Services\ImageUploader::targetDimensions(0, 0, 1440), [0, 0]);
    eq(\App\Services\ImageUploader::targetDimensions(-5, 10, 1440), [0, 0]);
    eq(\App\Services\ImageUploader::targetDimensions(800, 600, 0), [800, 600]);
});
test('allowed image types are the four raster formats and never SVG', function () {
    $mimes = \App\Services\ImageUploader::allowedMimes();
    ok(in_array('image/png', $mimes, true));
    ok(in_array('image/jpeg', $mimes, true));
    ok(in_array('image/webp', $mimes, true));
    ok(in_array('image/gif', $mimes, true));
    ok(!in_array('image/svg+xml', $mimes, true));
    eq(count($mimes), 4);
});
test('GIF uploads get the bigger byte ceiling', function () {
    ok(\App\Services\ImageUploader::maxBytesFor('image/gif') > \App\Services\ImageUploader::maxBytesFor('image/png'));
    eq(\App\Services\ImageUploader::maxBytesFor('image/jpeg'), \App\Services\ImageUploader::MAX_BYTES_IMAGE);
});
test('stored extension follows the real type, not the uploaded name', function () {
    eq(\App\Services\ImageUploader::extensionFor('image/gif'), 'gif');
    eq(\App\Services\ImageUploader::extensionFor('image/png'), 'png');
    eq(\App\Services\ImageUploader::extensionFor('image/webp'), 'webp');
    eq(\App\Services\ImageUploader::extensionFor('image/jpeg'), 'jpg');
});
