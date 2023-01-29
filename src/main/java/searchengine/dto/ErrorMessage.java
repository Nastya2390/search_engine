package searchengine.dto;

public enum ErrorMessage {

    IndexingIsNotStopped("Индексация не остановлена"),
    IndexingIsStoppedByUser("Индексация остановлена пользователем"),
    InterruptedExceptionOccuredOnStopIndexing("Произошел InterruptedException при остановке индексации"),
    PageIsOutOfConfigFile("Данная страница находится за пределами сайтов, указанных в конфигурационном файле"),
    SiteIsNotFoundByUrl("Не найден сайт по url"),
    SiteIsSeveralTimesUsedInConfig("Сайт несколько раз указан в конфигурационном файле"),
    NoSitesDataInConfigFile("В конфигурационном файле остутствуют url сайтов для индексации"),
    NoConnectionToSite("Отсутствует соединение с сайтом"),
    IndexingIsInProcess("Индексация уже запущена. Перед запуском переиндексации нужно остановить текущий запуск индексации"),
    LemmasDoublesFoundAtOneSite("Найдены дубли лемм на одном сайте"),

    IndexingIsNotRun("Индексация не запущена");

    private String value;

    ErrorMessage(){}

    ErrorMessage(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

}
