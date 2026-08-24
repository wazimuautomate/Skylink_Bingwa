-- Skylink Bingwa offers catalogue. Optional: the admin panel creates this table
-- automatically. Import this once (phpMyAdmin → Import) to seed it with the
-- current catalogue so the app fetches real offers immediately.

CREATE TABLE IF NOT EXISTS offers (
    offer_id   VARCHAR(32) PRIMARY KEY,
    category   VARCHAR(16) NOT NULL,
    name       VARCHAR(64) NOT NULL,
    price      INT NOT NULL,
    validity   VARCHAR(32) NOT NULL,
    band       VARCHAR(16) NOT NULL,
    daily_rule VARCHAR(20) NOT NULL DEFAULT 'BUY_AGAIN_TODAY',
    -- Safaricom's time-of-day selling window, Nairobi wall clock. NULL = all day.
    available_from TIME NULL DEFAULT NULL,
    available_to   TIME NULL DEFAULT NULL,
    active     TINYINT NOT NULL DEFAULT 1,
    sort_order INT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO offers (offer_id, category, name, price, validity, band, daily_rule, active, sort_order) VALUES
    ('data_1','DATA','1GB',19,'1 Hr','Hourly','ONCE_PER_DAY',1,1),
    ('data_2','DATA','250MB',20,'24 Hrs','Daily','ONCE_PER_DAY',1,2),
    ('data_3','DATA','1.5GB',50,'3 Hrs','Hourly','ONCE_PER_DAY',1,3),
    ('data_5','DATA','1GB',95,'24 Hrs','Daily','ONCE_PER_DAY',1,5),
    ('data_6','DATA','2GB',110,'24 Hrs','Daily','BUY_AGAIN_TODAY',1,6),
    ('data_7','DATA','350MB',49,'7 days','Weekly','ONCE_PER_DAY',1,7),
    ('data_8','DATA','2.5GB',300,'7 days','Weekly','ONCE_PER_DAY',1,8),
    ('data_9','DATA','6GB',700,'7 days','Weekly','ONCE_PER_DAY',1,9),
    ('data_10','DATA','1.2GB',250,'30 days','Monthly','ONCE_PER_DAY',1,10),
    ('data_11','DATA','2.5GB',500,'30 days','Monthly','ONCE_PER_DAY',1,11),
    ('data_12','DATA','10GB',1000,'30 days','Monthly','ONCE_PER_DAY',1,12),
    ('data_13','DATA','8GB + 400 Min',1005,'30 days','Monthly','ONCE_PER_DAY',1,13),
    ('sms_1','SMS','10 SMS',5,'24 Hrs','Daily','BUY_AGAIN_TODAY',1,20),
    ('sms_2','SMS','200 SMS',10,'24 Hrs','Daily','BUY_AGAIN_TODAY',1,21),
    ('sms_3','SMS','1,000 SMS',30,'7 days','Weekly','BUY_AGAIN_TODAY',1,22),
    ('sms_4','SMS','1,500 SMS',101,'30 days','Monthly','BUY_AGAIN_TODAY',1,23),
    ('sms_5','SMS','3,500 SMS',201,'30 days','Monthly','BUY_AGAIN_TODAY',1,24),
    ('min_1','MINUTES','20 Min',22,'Midnight','Daily','BUY_AGAIN_TODAY',1,30),
    ('min_2','MINUTES','35 Min',23,'2 Hrs','Hourly','BUY_AGAIN_TODAY',1,31),
    ('min_3','MINUTES','45 Min',24,'3 Hrs','Hourly','BUY_AGAIN_TODAY',1,32),
    ('min_4','MINUTES','50 Min',48,'Midnight','Daily','BUY_AGAIN_TODAY',1,33),
    ('min_5','MINUTES','250 Min',205,'7 days','Weekly','BUY_AGAIN_TODAY',1,34),
    ('min_6','MINUTES','100 Min',105,'Midnight','Daily','BUY_AGAIN_TODAY',1,35),
    ('min_7','MINUTES','300 Min',499,'30 days','Monthly','BUY_AGAIN_TODAY',1,36),
    ('min_8','MINUTES','800 Min',950,'30 days','Monthly','BUY_AGAIN_TODAY',1,37),
    ('spec_1','SPECIAL','1GB',21,'1 Hr','Hourly','ONCE_PER_DAY',1,40),
    ('spec_2','SPECIAL','1.5GB',51,'3 Hrs','Hourly','ONCE_PER_DAY',1,41),
    ('spec_3','SPECIAL','2GB',110,'24 Hrs','Daily','BUY_AGAIN_TODAY',1,42)
ON DUPLICATE KEY UPDATE
    category=VALUES(category), name=VALUES(name), price=VALUES(price),
    validity=VALUES(validity), band=VALUES(band), daily_rule=VALUES(daily_rule),
    active=VALUES(active), sort_order=VALUES(sort_order);
