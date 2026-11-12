package kr.ac.chosun.mspace.sensor.application;

/** 1분마다 빈 방 불 켜짐을 본다. 돌려주는 값은 이번에 알림을 보낸 사람 수 */
public interface LightLeftOnUseCase {

    int check();
}
