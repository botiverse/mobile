#ifndef SLOCK_OHOS_IMAGE_REQUEST_SINGLE_FLIGHT_H
#define SLOCK_OHOS_IMAGE_REQUEST_SINGLE_FLIGHT_H

#include <cstdint>
#include <mutex>
#include <unordered_map>
#include <utility>
#include <vector>

template <typename Key, typename Subscriber>
class SlockOhosImageRequestSingleFlight final {
 public:
    struct JoinResult final {
        uint64_t flightId = 0;
        uint64_t subscriberId = 0;
        bool shouldStart = false;
        size_t subscriberCount = 0;
    };

    template <typename CachedValue>
    struct JoinOrCachedResult final {
        CachedValue cachedValue{};
        JoinResult join{};
        bool cacheHit = false;
    };

    JoinResult Join(const Key &key, Subscriber subscriber) {
        std::lock_guard<std::mutex> lock(mutex_);
        return JoinLocked(key, std::move(subscriber));
    }

    template <typename CachedValue, typename CacheLookup>
    JoinOrCachedResult<CachedValue> JoinOrGetCached(const Key &key,
                                                    Subscriber subscriber,
                                                    CacheLookup cache_lookup) {
        std::lock_guard<std::mutex> lock(mutex_);
        auto cached_value = cache_lookup();
        if (cached_value) {
            return JoinOrCachedResult<CachedValue>{std::move(cached_value), JoinResult{}, true};
        }
        return JoinOrCachedResult<CachedValue>{
            CachedValue{},
            JoinLocked(key, std::move(subscriber)),
            false,
        };
    }

    template <typename Publish>
    std::vector<Subscriber> CompleteWithPublication(const Key &key,
                                                     uint64_t flight_id,
                                                     Publish publish) {
        std::lock_guard<std::mutex> lock(mutex_);
        auto existing = flights_.find(key);
        if (existing == flights_.end() || existing->second.id != flight_id) {
            return {};
        }
        publish();
        return CompleteLocked(existing);
    }

    bool Detach(const Key &key, uint64_t flight_id, uint64_t subscriber_id) {
        std::lock_guard<std::mutex> lock(mutex_);
        auto existing = flights_.find(key);
        if (existing == flights_.end() || existing->second.id != flight_id) {
            return false;
        }
        return existing->second.subscribers.erase(subscriber_id) > 0;
    }

    std::vector<Subscriber> Complete(const Key &key, uint64_t flight_id) {
        std::lock_guard<std::mutex> lock(mutex_);
        auto existing = flights_.find(key);
        if (existing == flights_.end() || existing->second.id != flight_id) {
            return {};
        }
        return CompleteLocked(existing);
    }

    size_t ActiveFlightCount() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return flights_.size();
    }

 private:
    struct Flight final {
        uint64_t id = 0;
        std::unordered_map<uint64_t, Subscriber> subscribers;
    };

    using FlightIterator = typename std::unordered_map<Key, Flight>::iterator;

    JoinResult JoinLocked(const Key &key, Subscriber subscriber) {
        auto existing = flights_.find(key);
        const uint64_t subscriber_id = next_subscriber_id_++;
        if (existing != flights_.end()) {
            existing->second.subscribers.emplace(subscriber_id, std::move(subscriber));
            return JoinResult{
                existing->second.id,
                subscriber_id,
                false,
                existing->second.subscribers.size(),
            };
        }

        const uint64_t flight_id = next_flight_id_++;
        Flight flight;
        flight.id = flight_id;
        flight.subscribers.emplace(subscriber_id, std::move(subscriber));
        flights_.emplace(key, std::move(flight));
        return JoinResult{flight_id, subscriber_id, true, 1};
    }

    std::vector<Subscriber> CompleteLocked(FlightIterator existing) {
        std::vector<Subscriber> subscribers;
        subscribers.reserve(existing->second.subscribers.size());
        for (auto &entry : existing->second.subscribers) {
            subscribers.push_back(std::move(entry.second));
        }
        flights_.erase(existing);
        return subscribers;
    }

    mutable std::mutex mutex_;
    std::unordered_map<Key, Flight> flights_;
    uint64_t next_flight_id_ = 1;
    uint64_t next_subscriber_id_ = 1;
};

#endif  // SLOCK_OHOS_IMAGE_REQUEST_SINGLE_FLIGHT_H
