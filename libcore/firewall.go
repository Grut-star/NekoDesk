package libcore

import (
	"sync"
	"github.com/Grut-star/shared-firewall" // Импортируем ваш новый пакет
)

// FirewallCallback — интерфейс, который будет реализован в Kotlin
type FirewallCallback interface {
	// Синхронный вызов для DNS (ждет ответа пользователя)
	AskDnsPermission(uid int, domain string) bool

	// Асинхронный вызов для прямых IP-соединений (только уведомление)
	NotifyDirectIpBlocked(uid int, ip string)
}

var (
	// Глобальная переменная для хранения ссылки на Kotlin-класс
	fwCallback FirewallCallback

	// Мьютекс для создания "очереди" всплывающих окон
	dnsPopupMutex sync.Mutex
)

// Создаем структуру-обертку, которая реализует интерфейс из shared-firewall
type firewallInterceptorImpl struct{}

func (i *firewallInterceptorImpl) AskDnsPermission(uid int, domain string) bool {
	return HandleDnsRequest(uid, domain)
}

func (i *firewallInterceptorImpl) NotifyDirectIpBlocked(uid int, ip string) {
	HandleDirectIpBlock(uid, ip)
}

// Инициализируем глобальный перехватчик при старте VPN
func RegisterFirewallCallback(cb FirewallCallback) {
	fwCallback = cb

	if cb != nil {
		// Брандмауэр включен: отдаем ядру нашу реализацию
		sharedfirewall.GlobalInterceptor = &firewallInterceptorImpl{}
	} else {
		// Брандмауэр выключен: очищаем перехватчик (ядро будет пропускать трафик)
		sharedfirewall.GlobalInterceptor = nil
	}
}


// --- ФУНКЦИИ ДЛЯ ВЫЗОВА ИЗ ВНУТРЕННОСТЕЙ ЯДРА (tun2socks / DNS-модуля) ---

// HandleDnsRequest приостанавливает горутину DNS-запроса и показывает UI.
// Возвращает true, если юзер разрешил, и false, если запретил.
func HandleDnsRequest(uid int, domain string) bool {
	// Если брандмауэр выключен (коллбек не передан), по умолчанию всё разрешаем
	if fwCallback == nil {
		return true
	}

	// БЛОКИРУЕМ ОЧЕРЕДЬ
	// Если окно уже висит, эта горутина "уснет".
	// Она проснется и пойдет дальше только когда предыдущий запрос сделает Unlock().
	dnsPopupMutex.Lock()

	// defer гарантирует, что мы разблокируем очередь при выходе из функции,
	// даже если Android крашнется или вернет ошибку.
	defer dnsPopupMutex.Unlock()

	// СВЯЗЬ С ANDROID
	// Выполнение блокируется здесь, пока Kotlin не вернет return true/false
	return fwCallback.AskDnsPermission(uid, domain)
}

// HandleDirectIpBlock вызывается, когда пакет уже дропнут.
func HandleDirectIpBlock(uid int, ip string) {
	if fwCallback != nil {
		// Запускаем в отдельной горутине, чтобы ядро не ждало завершения JNI-вызова
		go fwCallback.NotifyDirectIpBlocked(uid, ip)
	}
}