-- 관리자 "지금" 탭(요구사항 Q-21, 11/9): SCD40이 CO2와 같이 재는 온도·습도를 CO2 이벤트에 같이 받는다.
-- 둘 다 선택 칸이라 지금 장치(CO2만 보냄)는 그대로 돈다. 재실(OCCUPANCY) 이벤트에는 넣지 않는다.
-- 범위는 SCD40 측정 범위(-10–60℃, 0–100%)보다 조금 넓게: 센서 고장 값도 남겨서 관리자가 보게 한다.
ALTER TABLE sensor_event
    ADD COLUMN temperature_c numeric(4, 1) CHECK (temperature_c BETWEEN -40 AND 85),
    ADD COLUMN humidity_pct  numeric(4, 1) CHECK (humidity_pct BETWEEN 0 AND 100),
    ADD CONSTRAINT climate_only_with_co2 CHECK (kind = 'CO2' OR (temperature_c IS NULL AND humidity_pct IS NULL));

-- 최근 온도·습도 찾기(관리자 "지금" 탭, 10초마다). V11 인덱스에는 이 칸이 없어서, 온도를 한 번도 안 보낸 방은
-- LIMIT 1이 그 방 CO2 기록을 끝까지 읽는다. 값이 있는 줄만 담은 작은 인덱스로 바로 끝나게 한다
CREATE INDEX sensor_event_room_temperature ON sensor_event (room_id, observed_at DESC, id DESC)
    WHERE kind = 'CO2' AND temperature_c IS NOT NULL;
CREATE INDEX sensor_event_room_humidity ON sensor_event (room_id, observed_at DESC, id DESC)
    WHERE kind = 'CO2' AND humidity_pct IS NOT NULL;
