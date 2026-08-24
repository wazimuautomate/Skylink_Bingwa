<?php
/**
 * Canonical reference data for a fresh admin-v2 install:
 *  - the full permission set and the default roles (§17),
 *  - the app's shipped catalogue and support/config defaults, mirroring the Android app
 *    + legacy server so the first publish matches the app.
 *
 * Editing this file changes only NEW installs / empty tables. It never clobbers data
 * an administrator has already edited (see Seeder).
 */

return [

    // permission_key => [group, label]
    'permissions' => [
        'dashboard.view'      => ['Dashboard', 'View dashboard'],
        'offers.view'         => ['Offers', 'View offers'],
        'offers.create'       => ['Offers', 'Create offers'],
        'offers.edit'         => ['Offers', 'Edit offers'],
        'offers.archive'      => ['Offers', 'Archive/restore offers'],
        'offers.delete'       => ['Offers', 'Delete unused draft offers'],
        'billboards.manage'   => ['Billboards', 'Manage billboard adverts'],
        'notifications.create'=> ['Notifications', 'Create notification campaigns'],
        'notifications.schedule'=> ['Notifications', 'Schedule / cancel campaigns'],
        'payments.view'       => ['Payments', 'View payment operations'],
        'payments.export'     => ['Payments', 'Export payment CSV'],
        'support.edit'        => ['Support', 'Edit support & payment routes'],
        'config.edit'         => ['App config', 'Edit remote app configuration'],
        'releases.manage'     => ['Updates', 'Manage app version / update rules'],
        'publish.execute'     => ['Publishing', 'Publish a configuration release'],
        'rollback.execute'    => ['Publishing', 'Roll back to a previous release'],
        'audit.view'          => ['Audit', 'View the audit log'],
        'admins.manage'       => ['Settings', 'Manage administrators & roles'],
    ],

    // Two account types only (Super Admin + Admin). Access is page-level, so there are
    // no seeded roles or a permission matrix.
    'roles' => [],

    // Support / payment public config defaults. Numbers are BLANK on purpose — the owner
    // sets the real offline Till/Paybill and support numbers from the admin Support page.
    'support_config' => [
        'till_number'      => '',
        'paybill_number'   => '',
        'support_number'   => '',
        'support_whatsapp' => '',
        'offline_self_instructions'  => 'On M-PESA, choose Lipa na M-PESA > Buy Goods and Services. Enter the Till number shown above, then the exact amount and your PIN.',
        'offline_other_instructions' => 'On M-PESA, choose Lipa na M-PESA > Pay Bill. Enter the Business (Paybill) number shown above, use the recipient number as the Account number, then the exact amount and your PIN.',
        'support_banner'   => '',
        'working_hours'    => '',
    ],

    'app_config' => [
        'maintenance_mode'       => 0,
        'sync_interval_minutes'  => 360,
        'general_support_message' => '',
    ],

    'app_version' => [
        'latest_version_code' => 1,
        'latest_version_name' => '1.0.0',
        'min_supported_version_code' => 1,
        'mandatory' => 0,
        'play_store_url' => 'https://play.google.com/store/apps/details?id=com.bingwasokoni',
        'apk_url' => 'https://github.com/wazimuautomate/Skylink_Bingwa/releases',
        'apk_sha256' => '',
        'rollout_percent' => 100,
        'release_notes' => 'Initial public release.',
    ],

    // [offer_id, category, name, price, validity, band, once?] — mirror the app catalogue.
    'offers' => [
        ['data_1',  'DATA', '1GB',           19,   '1 Hr',     'Hourly',  true],
        ['data_2',  'DATA', '250MB',         20,   '24 Hrs',   'Daily',   true],
        ['data_3',  'DATA', '1.5GB',         50,   '3 Hrs',    'Hourly',  true],
        ['data_5',  'DATA', '1GB',           95,   '24 Hrs',   'Daily',   true],
        ['data_6',  'DATA', '2GB',           110,  '24 Hrs',   'Daily',   false],
        ['data_7',  'DATA', '350MB',         49,   '7 days',   'Weekly',  true],
        ['data_8',  'DATA', '2.5GB',         300,  '7 days',   'Weekly',  true],
        ['data_9',  'DATA', '6GB',           700,  '7 days',   'Weekly',  true],
        ['data_10', 'DATA', '1.2GB',         250,  '30 days',  'Monthly', true],
        ['data_11', 'DATA', '2.5GB',         500,  '30 days',  'Monthly', true],
        ['data_12', 'DATA', '10GB',          1000, '30 days',  'Monthly', true],
        ['data_13', 'DATA', '8GB + 400 Min', 1005, '30 days',  'Monthly', true],
        ['sms_1',   'SMS',  '10 SMS',        5,    '24 Hrs',   'Daily',   false],
        ['sms_2',   'SMS',  '200 SMS',       10,   '24 Hrs',   'Daily',   false],
        ['sms_3',   'SMS',  '1,000 SMS',     30,   '7 days',   'Weekly',  false],
        ['sms_4',   'SMS',  '1,500 SMS',     101,  '30 days',  'Monthly', false],
        ['sms_5',   'SMS',  '3,500 SMS',     201,  '30 days',  'Monthly', false],
        ['min_1',   'MINUTES', '20 Min',     22,   'Midnight', 'Daily',   false],
        ['min_2',   'MINUTES', '35 Min',     23,   '2 Hrs',    'Hourly',  false],
        ['min_3',   'MINUTES', '45 Min',     24,   '3 Hrs',    'Hourly',  false],
        ['min_4',   'MINUTES', '50 Min',     48,   'Midnight', 'Daily',   false],
        ['min_5',   'MINUTES', '250 Min',    205,  '7 days',   'Weekly',  false],
        ['min_6',   'MINUTES', '100 Min',    105,  'Midnight', 'Daily',   false],
        ['min_7',   'MINUTES', '300 Min',    499,  '30 days',  'Monthly', false],
        ['min_8',   'MINUTES', '800 Min',    950,  '30 days',  'Monthly', false],
        ['spec_1',  'SPECIAL', '1GB',        21,   '1 Hr',     'Hourly',  true],
        ['spec_2',  'SPECIAL', '1.5GB',      51,   '3 Hrs',    'Hourly',  true],
        ['spec_3',  'SPECIAL', '2GB',        110,  '24 Hrs',   'Daily',   false],
    ],

    // Safaricom senders.
    'sender_ids' => [
        ['Safaricom', 'Bundle delivery + general Safaricom messages'],
        ['SAF_Balance', 'Balance / deal-of-the-day notices'],
        ['SAF_OfaMOTO', 'Minutes / offers delivery'],
    ],

];
