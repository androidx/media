#include <mutex>
#include <condition_variable>
#include <deque>
#include <chrono>

template<class T>
class BlockingQueue {
public:
    using size_type = typename std::deque<T>::size_type;

public:
    BlockingQueue(const int cap = -1) : m_maxCapacity(cap) {}

    ~BlockingQueue() {}

    BlockingQueue(const BlockingQueue &) = delete;

    BlockingQueue &operator=(const BlockingQueue &) = delete;

public:
    void put(const T t);

    T take();

    bool empty() const {
        std::lock_guard<std::mutex> lock(m_mutex);
        return m_queue.empty();
    }

    bool full() const {
        if (-1 == m_maxCapacity)
            return false;
        std::lock_guard<std::mutex> lock(m_mutex);
        return m_queue.size() >= m_maxCapacity;
    }

    size_type size() {
        std::lock_guard<std::mutex> lock(m_mutex);
        return m_queue.size();
    }

public:
    bool offer(const T t);

    bool poll(T &t);

    bool offer(const T t, long mils);

    bool poll(T &t, long mils);

private:
    std::deque<T> m_queue;
    const int m_maxCapacity;
    mutable std::mutex m_mutex;
    std::condition_variable m_cond_empty;
    std::condition_variable m_cond_full;
};

template<class T>
void BlockingQueue<T>::put(const T t) {
    std::unique_lock<std::mutex> lock(m_mutex);
    if (m_maxCapacity != -1) {
        m_cond_full.wait(lock, [this] { return m_queue.size() < m_maxCapacity; });
    }
    m_queue.push_back(t);
    m_cond_empty.notify_all();
}

template<class T>
T BlockingQueue<T>::take() {
    std::unique_lock<std::mutex> lock(m_mutex);
    // take必须判断队列为空
    m_cond_empty.wait(lock, [&]() { return !m_queue.empty(); });
    auto res = m_queue.front();
    m_queue.pop_front();
    m_cond_full.notify_all();
    return res;
}

template<class T>
bool BlockingQueue<T>::offer(const T t) {
    std::unique_lock<std::mutex> lock(m_mutex);
    if (m_maxCapacity != -1 && m_queue.size() >= m_maxCapacity) {
        return false;
    }
    m_queue.push_back(t);
    m_cond_empty.notify_all();
    return true;
}

template<class T>
bool BlockingQueue<T>::poll(T &t) {
    std::unique_lock<std::mutex> lock(m_mutex);
    if (m_queue.empty()) {
        return false;
    }
    t = m_queue.front();
    m_queue.pop_front();
    m_cond_full.notify_all();
    return true;
}

template<class T>
bool BlockingQueue<T>::offer(const T t, long mils) {
    std::unique_lock<std::mutex> lock(m_mutex);
    std::chrono::milliseconds time(mils);
    if (m_maxCapacity != -1) {
        bool result = m_cond_full.wait_for(lock, time,
                                       [&] { return m_queue.size() < m_maxCapacity; });
        if (!result) {
            return false;
        }
    }
    m_queue.push_back(t);
    m_cond_empty.notify_all();
    return true;
}

template<class T>
bool BlockingQueue<T>::poll(T &t, long mils) {
    std::chrono::milliseconds time(mils);
    std::unique_lock<std::mutex> lock(m_mutex);
    bool result = m_cond_empty.wait_for(lock, time,
                                        [&] { return !m_queue.empty(); });
    if (!result) {
        return false;
    }
    t = m_queue.front();
    m_queue.pop_front();
    m_cond_full.notify_all();
    return true;
}