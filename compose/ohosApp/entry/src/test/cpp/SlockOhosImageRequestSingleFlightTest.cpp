#include "../../main/cpp/SlockOhosImageRequestSingleFlight.h"

#include <cassert>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

namespace {

using Flight = SlockOhosImageRequestSingleFlight<std::string, int>;
using CachedValue = std::shared_ptr<int>;

class TestCache final {
 public:
    CachedValue Get(const std::string &key) {
        std::lock_guard<std::mutex> lock(mutex_);
        const auto existing = values_.find(key);
        return existing == values_.end() ? nullptr : existing->second;
    }

    void Put(const std::string &key, CachedValue value) {
        std::lock_guard<std::mutex> lock(mutex_);
        values_[key] = std::move(value);
    }

 private:
    std::mutex mutex_;
    std::unordered_map<std::string, CachedValue> values_;
};

void ThreeCallersStartOneProducer() {
    Flight flight;
    const auto first = flight.Join("avatar", 1);
    const auto second = flight.Join("avatar", 2);
    const auto third = flight.Join("avatar", 3);

    assert(first.shouldStart);
    assert(!second.shouldStart);
    assert(!third.shouldStart);
    assert(first.flightId == second.flightId);
    assert(first.flightId == third.flightId);
    assert(flight.ActiveFlightCount() == 1);

    const auto subscribers = flight.Complete("avatar", first.flightId);
    assert(subscribers.size() == 3);
    assert(flight.ActiveFlightCount() == 0);
}

void OneSubscriberCanDetachWithoutCancellingTheFlight() {
    Flight flight;
    const auto first = flight.Join("avatar", 1);
    const auto second = flight.Join("avatar", 2);
    const auto third = flight.Join("avatar", 3);

    assert(flight.Detach("avatar", first.flightId, second.subscriberId));
    assert(third.flightId == first.flightId);
    const auto subscribers = flight.Complete("avatar", first.flightId);
    assert(subscribers.size() == 2);
    assert((subscribers[0] == 3 || subscribers[1] == 3));
    assert((subscribers[0] == 1 || subscribers[1] == 1));
}

void CompletionClearsTheFlightAndAllowsRetry() {
    Flight flight;
    const auto first = flight.Join("avatar", 1);
    assert(flight.Complete("avatar", first.flightId).size() == 1);

    const auto retry = flight.Join("avatar", 2);
    assert(retry.shouldStart);
    assert(retry.flightId != first.flightId);
    assert(flight.Complete("avatar", retry.flightId).size() == 1);
}

void LateOldCompletionCannotClearANewerRetry() {
    Flight flight;
    const auto first = flight.Join("avatar", 1);
    assert(flight.Complete("avatar", first.flightId).size() == 1);

    const auto retry = flight.Join("avatar", 2);
    bool stale_published = false;
    assert(flight.CompleteWithPublication("avatar", first.flightId, [&]() {
        stale_published = true;
    }).empty());
    assert(!stale_published);
    assert(flight.ActiveFlightCount() == 1);
    assert(flight.Complete("avatar", retry.flightId).size() == 1);
}

void DifferentRawUrlsNeverJoin() {
    Flight flight;
    const auto first = flight.Join("https://www.gravatar.com/avatar/hash?s=121", 1);
    const auto second = flight.Join("https://www.gravatar.com/avatar/hash?s=256", 2);
    assert(first.shouldStart);
    assert(second.shouldStart);
    assert(first.flightId != second.flightId);
    assert(flight.ActiveFlightCount() == 2);
}

void CacheMissCannotResumeAsANewFlightAfterCompletion() {
    Flight flight;
    TestCache cache;
    const auto first = flight.Join("avatar", 1);

    std::mutex control_mutex;
    std::condition_variable control_condition;
    bool lookup_entered = false;
    bool resume_lookup = false;
    Flight::JoinOrCachedResult<CachedValue> second;
    std::vector<int> completed;

    std::thread second_caller([&]() {
        second = flight.JoinOrGetCached<CachedValue>("avatar", 2, [&]() {
            std::unique_lock<std::mutex> control_lock(control_mutex);
            lookup_entered = true;
            control_condition.notify_all();
            control_condition.wait(control_lock, [&]() { return resume_lookup; });
            return cache.Get("avatar");
        });
    });

    {
        std::unique_lock<std::mutex> control_lock(control_mutex);
        control_condition.wait(control_lock, [&]() { return lookup_entered; });
    }

    std::thread producer_completion([&]() {
        completed = flight.CompleteWithPublication("avatar", first.flightId, [&]() {
            cache.Put("avatar", std::make_shared<int>(42));
        });
    });

    {
        std::lock_guard<std::mutex> control_lock(control_mutex);
        resume_lookup = true;
    }
    control_condition.notify_all();

    second_caller.join();
    producer_completion.join();

    assert(!second.cacheHit);
    assert(!second.join.shouldStart);
    assert(second.join.flightId == first.flightId);
    assert(completed.size() == 2);
    assert(flight.ActiveFlightCount() == 0);
    assert(*cache.Get("avatar") == 42);
}

void PublishedCacheAndFlightRemovalAreOneAtomicHandoff() {
    Flight flight;
    TestCache cache;
    const auto first = flight.Join("avatar", 1);

    std::mutex control_mutex;
    std::condition_variable control_condition;
    bool publication_entered = false;
    bool finish_completion = false;
    Flight::JoinOrCachedResult<CachedValue> second;

    std::thread producer_completion([&]() {
        const auto completed = flight.CompleteWithPublication("avatar", first.flightId, [&]() {
            std::unique_lock<std::mutex> control_lock(control_mutex);
            publication_entered = true;
            control_condition.notify_all();
            control_condition.wait(control_lock, [&]() { return finish_completion; });
            control_lock.unlock();
            cache.Put("avatar", std::make_shared<int>(84));
        });
        assert(completed.size() == 1);
    });

    {
        std::unique_lock<std::mutex> control_lock(control_mutex);
        control_condition.wait(control_lock, [&]() { return publication_entered; });
    }

    std::thread second_caller([&]() {
        second = flight.JoinOrGetCached<CachedValue>(
            "avatar", 2, [&]() { return cache.Get("avatar"); });
    });

    {
        std::lock_guard<std::mutex> control_lock(control_mutex);
        finish_completion = true;
    }
    control_condition.notify_all();

    producer_completion.join();
    second_caller.join();

    assert(second.cacheHit);
    assert(second.cachedValue && *second.cachedValue == 84);
    assert(!second.join.shouldStart);
    assert(flight.ActiveFlightCount() == 0);
}

}  // namespace

int main() {
    ThreeCallersStartOneProducer();
    OneSubscriberCanDetachWithoutCancellingTheFlight();
    CompletionClearsTheFlightAndAllowsRetry();
    LateOldCompletionCannotClearANewerRetry();
    DifferentRawUrlsNeverJoin();
    CacheMissCannotResumeAsANewFlightAfterCompletion();
    PublishedCacheAndFlightRemovalAreOneAtomicHandoff();
    return 0;
}
