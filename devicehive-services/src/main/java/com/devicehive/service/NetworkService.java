package com.devicehive.service;

import com.devicehive.auth.AccessKeyAction;
import com.devicehive.auth.CheckPermissionsHelper;
import com.devicehive.auth.HiveAuthentication;
import com.devicehive.auth.HivePrincipal;
import com.devicehive.configuration.ConfigurationService;
import com.devicehive.configuration.Messages;
import com.devicehive.dao.NetworkDao;
import com.devicehive.dao.filter.AccessKeyBasedFilterForDevices;
import com.devicehive.dao.filter.AccessKeyBasedFilterForNetworks;
import com.devicehive.exceptions.ActionNotAllowedException;
import com.devicehive.exceptions.IllegalParametersException;
import com.devicehive.model.*;
import com.devicehive.model.updates.NetworkUpdate;
import com.devicehive.util.HiveValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.validation.constraints.NotNull;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Optional.*;

@Component
public class NetworkService {
    private static final Logger logger = LoggerFactory.getLogger(NetworkService.class);

    public static final String ALLOW_NETWORK_AUTO_CREATE = "allowNetworkAutoCreate";

    @Autowired
    private UserService userService;
    @Autowired
    private AccessKeyService accessKeyService;
    @Autowired
    private ConfigurationService configurationService;
    @Autowired
    private HiveValidator hiveValidator;
    @Autowired
    private NetworkDao networkDao;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Network getWithDevicesAndDeviceClasses(@NotNull Long networkId, @NotNull HiveAuthentication hiveAuthentication) {
        HiveAuthentication.HiveAuthDetails details = (HiveAuthentication.HiveAuthDetails) hiveAuthentication.getDetails();
        HivePrincipal principal = (HivePrincipal) hiveAuthentication.getPrincipal();

        Set<Long> permittedNetworks = permittedNetworksIds(principal.getKey());
        Set<String> permittedDevices = permittedDeviceGuids(principal.getKey());

        Optional<Network> result = of(principal)
                .flatMap(pr -> {
                    if (pr.getUser() != null)
                        return of(pr.getUser());
                    else if (pr.getKey() != null && pr.getKey().getUser() != null)
                        return of(pr.getKey().getUser());
                    else
                        return empty();
                }).flatMap(user -> {
                    Long idForFiltering = user.isAdmin() ? null : user.getId();
                    List<Network> found = networkDao.getNetworksByIdsAndUsers(idForFiltering,
                            Collections.singleton(networkId), permittedNetworks);
                    return found.stream().findFirst();
                }).map(network -> {
                    if (principal.getKey() != null) {
                        Set<AccessKeyPermission> permissions = principal.getKey().getPermissions();
                        Set<AccessKeyPermission> filtered = CheckPermissionsHelper
                                .filterPermissions(permissions, AccessKeyAction.GET_DEVICE,
                                        details.getClientInetAddress(), details.getOrigin());
                        if (filtered.isEmpty()) {
                            network.setDevices(Collections.emptySet());
                        }
                    }
                    if (permittedDevices != null && !permittedDevices.isEmpty()) {
                        Set<Device> allowed = network.getDevices().stream()
                                .filter(device -> permittedDevices.contains(device.getGuid()))
                                .collect(Collectors.toSet());
                        network.setDevices(allowed);
                    }
                    return network;
                });

        return result.orElse(null);
    }

    private Set<Long> permittedNetworksIds(AccessKey accessKey) {
        return ofNullable(accessKey)
                .map(AccessKey::getPermissions)
                .map(AccessKeyBasedFilterForNetworks::createExtraFilters)
                .map(filters -> filters.stream().map(AccessKeyBasedFilterForNetworks::getNetworkIds).filter(s -> s != null).collect(Collectors.toSet()))
                .flatMap(setOfSets -> {
                    Set<Long> networkIds = new HashSet<>();
                    setOfSets.forEach(networkIds::addAll);
                    return networkIds.isEmpty() ? empty() : of(networkIds);
                }).orElse(null);
    }

    private Set<String> permittedDeviceGuids(AccessKey accessKey) {
        return ofNullable(accessKey)
                .map(AccessKey::getPermissions)
                .map(AccessKeyBasedFilterForDevices::createExtraFilters)
                .map(filters -> filters.stream().map(AccessKeyBasedFilterForDevices::getDeviceGuids).filter(s -> s != null).collect(Collectors.toSet()))
                .map(setOfSets -> {
                    Set<String> deviceGuids = new HashSet<>();
                    setOfSets.forEach(deviceGuids::addAll);
                    return deviceGuids;
                }).orElse(null);
    }

    @Transactional
    public boolean delete(long id) {
        logger.trace("About to execute named query \"Network.deleteById\" for ");
        int result = networkDao.deleteById(id);
        logger.debug("Deleted {} rows from Network table", result);
        return result > 0;
    }

    @Transactional
    public Network create(Network newNetwork) {
        logger.debug("Creating network {}", newNetwork);
        if (newNetwork.getId() != null) {
            logger.error("Can't create network entity with id={} specified", newNetwork.getId());
            throw new IllegalParametersException(Messages.ID_NOT_ALLOWED);
        }
        List<Network> existing = networkDao.findByName(newNetwork.getName());
        if (!existing.isEmpty()) {
            logger.error("Network with name {} already exists", newNetwork.getName());
            throw new ActionNotAllowedException(Messages.DUPLICATE_NETWORK);
        }
        networkDao.persist(newNetwork);
        logger.info("Entity {} created successfully", newNetwork);
        return newNetwork;
    }

    @Transactional
    public Network update(@NotNull Long networkId, NetworkUpdate networkUpdate) {
        Network existing = networkDao.find(networkId);
        if (existing == null) {
            throw new NoSuchElementException(String.format(Messages.NETWORK_NOT_FOUND, networkId));
        }
        if (networkUpdate.getKey() != null) {
            existing.setKey(networkUpdate.getKey().orElse(null));
        }
        if (networkUpdate.getName() != null) {
            existing.setName(networkUpdate.getName().orElse(null));
        }
        if (networkUpdate.getDescription() != null) {
            existing.setDescription(networkUpdate.getDescription().orElse(null));
        }
        hiveValidator.validate(existing);
        return networkDao.merge(existing);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<Network> list(String name,
                              String namePattern,
                              String sortField,
                              boolean sortOrderAsc,
                              Integer take,
                              Integer skip,
                              HivePrincipal principal) {
        Optional<HivePrincipal> principalOpt = ofNullable(principal);
        principalOpt.map(HivePrincipal::getDevice).ifPresent(device -> {
            throw new ActionNotAllowedException(Messages.NO_ACCESS_TO_NETWORK);
        });
        return networkDao.list(name, namePattern, sortField, sortOrderAsc, take, skip, principalOpt);
    }

    @Transactional
    public Network createOrVerifyNetwork(Optional<Network> networkNullable) {
        //case network is not defined
        if (networkNullable == null || networkNullable.orElse(null) == null) {
            return null;
        }
        Network network = networkNullable.get();

        Optional<Network> storedOpt = findNetworkByIdOrName(network);
        if (storedOpt.isPresent()) {
            return validateNetworkKey(storedOpt.get(), network);
        } else {
            if (network.getId() != null) {
                throw new IllegalParametersException(Messages.INVALID_REQUEST_PARAMETERS);
            }
            boolean allowed = configurationService.getBoolean(ALLOW_NETWORK_AUTO_CREATE, false);
            if (allowed) {
                networkDao.persist(network);
            }
            return network;
        }
    }

    @Transactional
    public Network createOrUpdateNetworkByUser(Optional<Network> networkNullable, User user) {
        //case network is not defined
        if (networkNullable == null || networkNullable.orElse(null) == null) {
            return null;
        }

        Network network = networkNullable.orElse(null);

        Optional<Network> storedOpt = findNetworkByIdOrName(network);
        if (storedOpt.isPresent()) {
            Network stored = validateNetworkKey(storedOpt.get(), network);
            if (!userService.hasAccessToNetwork(user, stored)) {
                throw new ActionNotAllowedException(Messages.NO_ACCESS_TO_NETWORK);
            }
            return stored;
        } else {
            if (network.getId() != null) {
                throw new IllegalParametersException(Messages.INVALID_REQUEST_PARAMETERS);
            }
            boolean allowed = configurationService.getBoolean(ALLOW_NETWORK_AUTO_CREATE, false);
            if (user.isAdmin() || allowed) {
                networkDao.persist(network);
            } else {
                throw new ActionNotAllowedException(Messages.NETWORK_CREATION_NOT_ALLOWED);
            }
            return network;
        }
    }

    @Transactional
    public Network createOrVerifyNetworkByKey(Optional<Network> networkNullable, AccessKey key) {
        //case network is not defined
        if (networkNullable == null || networkNullable.orElse(null) == null) {
            return null;
        }

        Network network = networkNullable.orElse(null);

        Optional<Network> storedOpt = findNetworkByIdOrName(network);
        if (storedOpt.isPresent()) {
            Network stored = validateNetworkKey(storedOpt.get(), network);
            if (stored.getKey() != null && !accessKeyService.hasAccessToNetwork(key, stored)) {
                throw new ActionNotAllowedException(Messages.NO_ACCESS_TO_NETWORK);
            }
            return stored;
        } else {
            if (network.getId() != null) {
                throw new IllegalParametersException(Messages.INVALID_REQUEST_PARAMETERS);
            }
            boolean allowed = configurationService.getBoolean(ALLOW_NETWORK_AUTO_CREATE, false);
            if (allowed) {
                networkDao.persist(network);
            } else {
                throw new ActionNotAllowedException(Messages.NETWORK_CREATION_NOT_ALLOWED);
            }
            return network;
        }
    }

    private Optional<Network> findNetworkByIdOrName(Network network) {
        return ofNullable(network.getId())
                .map(id -> ofNullable(networkDao.find(id)))
                .orElseGet(() -> networkDao.findFirstByName(network.getName()));
    }

    private Network validateNetworkKey(Network stored, Network received) {
        if (stored.getKey() != null && !stored.getKey().equals(received.getKey())) {
            throw new ActionNotAllowedException(Messages.INVALID_NETWORK_KEY);
        }
        return stored;
    }
}