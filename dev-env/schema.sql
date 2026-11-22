-- Schema for the coupon service: tables only.
--
-- This file is the ONLY place the table layout is defined, and it is never applied by the
-- application itself: the application runs with DML rights only, so it cannot create or alter
-- tables at runtime. It is applied by a privileged operation instead — dev-env/init-db.sh against
-- the dev database, and the MySQL test container against its own (see
-- app/src/test/kotlin/local/exercise/coupons/testing/TestContainersConfiguration.kt). Both apply
-- it to a database that already exists, so creating and dropping the database is the caller's job.
--
-- Keep this file free of anything database-specific: no USE, no CREATE DATABASE.

CREATE TABLE IF NOT EXISTS merchants (
    id BIGINT NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS customers (
    id BIGINT NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS coupons (
    -- The collation is what makes coupon ids case-insensitive for lookups and for the primary
    -- key, while the stored spelling stays exactly as the merchant wrote it. Do not change it to
    -- a case-sensitive collation: CouponRepository depends on it (see its class comment).
    id             VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    merchant_id    BIGINT      NOT NULL,
    created_at     TIMESTAMP   NOT NULL,
    max_usage      INTEGER     NOT NULL,
    current_usage  INTEGER     NOT NULL,
    country        VARCHAR(2)  NOT NULL,
    PRIMARY KEY (merchant_id, id),
    CONSTRAINT fk_coupons_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id),
    CONSTRAINT ck_coupons_max_usage CHECK (max_usage >= 1),
    CONSTRAINT ck_coupons_current_usage CHECK (current_usage >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS coupon_usage (
    merchant_id  BIGINT      NOT NULL,
    -- Same collation as coupons.id so the foreign key keeps matching case-insensitively.
    coupon_id    VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    customer_id  BIGINT      NOT NULL,
    used_at      TIMESTAMP   NOT NULL,
    PRIMARY KEY (merchant_id, coupon_id, customer_id),
    CONSTRAINT fk_usage_coupon FOREIGN KEY (merchant_id, coupon_id) REFERENCES coupons (merchant_id, id),
    CONSTRAINT fk_usage_customer FOREIGN KEY (customer_id) REFERENCES customers (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
