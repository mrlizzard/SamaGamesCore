package net.samagames.core.api.player;

import com.mojang.authlib.GameProfile;
import net.samagames.api.player.AbstractPlayerData;
import net.samagames.api.player.IFinancialCallback;
import net.samagames.core.APIPlugin;
import net.samagames.core.ApiImplementation;
import net.samagames.persistanceapi.beans.players.PlayerBean;
import net.samagames.persistanceapi.beans.players.SanctionBean;
import net.samagames.tools.Reflection;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import redis.clients.jedis.Jedis;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.util.Date;
import java.util.UUID;

/*
 * This file is part of SamaGamesCore.
 *
 * SamaGamesCore is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * SamaGamesCore is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with SamaGamesCore.  If not, see <http://www.gnu.org/licenses/>.
 */
public class PlayerData extends AbstractPlayerData
{
    protected final ApiImplementation api;
    protected final PlayerDataManager manager;

    private PlayerBean playerBean;

    private long lastRefresh;
    private UUID playerUUID;

    private GameProfile fakeProfile;

    private UUID fakeUUID;

    private final static String key = "playerdata:";

    private SanctionBean muteSanction = null;

    private boolean loaded = false;

    protected PlayerData(UUID playerID, ApiImplementation api, PlayerDataManager manager)
    {
        this.playerUUID = playerID;
        this.api = api;
        this.manager = manager;
        //this.fakeUUID = UUID.randomUUID();
        // this.fakeUUID = playerID;

        playerBean = new PlayerBean(playerUUID,
                "",
                null,
                500,
                0,
                0,
                null,
                null,
                null,
                null,
                0);

        refreshData();
    }

    //Warning load all data soi may be heavy
    public boolean refreshData()
    {
        lastRefresh = System.currentTimeMillis();
        //Load from redis

        try(Jedis jedis = api.getBungeeResource()){
            //CacheLoader.load(jedis, key + playerUUID, playerBean);
            playerBean = api.getGameServiceManager().getPlayer(playerUUID, playerBean);
            if (jedis.exists("mute:" + playerUUID))
            {
                String by = jedis.hget("mute:" + playerUUID, "by");
                String expireAt = jedis.hget("mute:" + playerUUID, "expireAt");
                muteSanction = new SanctionBean(playerUUID,
                        SanctionBean.MUTE,
                        jedis.hget("mute:" + playerUUID, "reason"),
                        (by != null) ? UUID.fromString(by) : null,
                        (expireAt != null)? new Timestamp(Long.valueOf(expireAt)): null,
                        false);
            }
            /**
            if (hasNickname()) {
                this.fakeUUID = this.api.getUUIDTranslator().getUUID(playerBean.getNickName(), true);
                this.fakeProfile = new ProfileLoader(playerUUID.toString(), playerBean.getNickName(), fakeUUID.toString()).loadProfile();
            }**/
            loaded = true;
            return true;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    public void updateData()
    {
        if(playerBean != null && loaded)
        {
            //Save in redisResource
            //Jedis jedis = api.getBungee();
            //Generated class so FUCK IT i made it static
            //CacheLoader.send(jedis, key + playerUUID, playerBean);
            try {
                api.getGameServiceManager().updatePlayer(playerBean);
            } catch (Exception e) {
                e.printStackTrace();
            }
            //jedis.close();
        }
    }

    public SanctionBean getMuteSanction()
    {
        return muteSanction;
    }

    @Override
    public void creditCoins(long amount, String reason, boolean applyMultiplier, IFinancialCallback financialCallback)
    {
        creditEconomy(0, amount, reason, applyMultiplier, financialCallback);
    }

    @Override
    public void creditStars(long amount, String reason, boolean applyMultiplier, IFinancialCallback financialCallback)
    {
        this.assertHub();
        creditEconomy(1, amount, reason, applyMultiplier, financialCallback);
    }

    @Override
    public void creditPowders(long amount, IFinancialCallback financialCallback)
    {
        this.assertHub();
        creditEconomy(2, amount, null, false, financialCallback);
    }

    private void creditEconomy(int type, long amountFinal, String reason, boolean applyMultiplier, IFinancialCallback financialCallback)
    {
        int game = 0;
        APIPlugin.getInstance().getExecutor().execute(() -> {
            try
            {
                long amount = amountFinal;
                String message = null;

                Multiplier multiplier = manager.getEconomyManager().getPromotionMultiplier(type, game);
                if (applyMultiplier)
                {
                    multiplier.cross(manager.getEconomyManager().getGroupMultiplier(getPlayerID()));
                }

                amount *= multiplier.getGlobalAmount();

                if (reason != null)
                {
                    message = manager.getEconomyManager().getCreditMessage(amount, type, reason, multiplier);

                    if (Bukkit.getPlayer(getPlayerID()) != null)
                        Bukkit.getPlayer(getPlayerID()).sendMessage(message);
                }

                //edit here for more type of coins
                long result = (type == 0) ? increaseCoins(amount) : (type == 1) ? increaseStars(amount) : increasePowders(amount);

                if (financialCallback != null)
                    financialCallback.done(result, amount, null);

            } catch (Exception e)
            {
                e.printStackTrace();
            }
        });
    }

    @Override
    public void withdrawCoins(long amount, IFinancialCallback financialCallback)
    {
        APIPlugin.getInstance().getExecutor().execute(() -> {
            long result = decreaseCoins(amount);
            if (financialCallback != null)
                financialCallback.done(result, -amount, null);

        });
    }

    @Override
    public void withdrawStars(long amount, IFinancialCallback financialCallback)
    {
        this.assertHub();

        APIPlugin.getInstance().getExecutor().execute(() -> {
            long result = decreaseStars(amount);

            if (financialCallback != null)
                financialCallback.done(result, -amount, null);

        });
    }

    @Override
    public void withdrawPowders(long amount, IFinancialCallback financialCallback)
    {
        this.assertHub();

        APIPlugin.getInstance().getExecutor().execute(() -> {
            long result = decreasePowders(amount);

            if (financialCallback != null)
                financialCallback.done(result, -amount, null);

        });
    }

    @Override
    public long increaseCoins(long incrBy) {
        refreshData();
        int result = (int) (playerBean.getCoins() + incrBy);
        playerBean.setCoins(result);
        updateData();
        return result;
    }

    @Override
    public long increaseStars(long incrBy) {
        this.assertHub();

        refreshData();
        int result = (int) (playerBean.getStars() + incrBy);
        playerBean.setStars(result);
        updateData();
        return result;
    }

    @Override
    public long increasePowders(long incrBy) {
        this.assertHub();

        refreshData();
        int result = (int) (playerBean.getPowders() + incrBy);
        playerBean.setPowders(result);
        updateData();
        return result;
    }

    @Override
    public long decreaseCoins(long decrBy)
    {
        return increaseCoins(-decrBy);
    }

    @Override
    public long decreaseStars(long decrBy)
    {
        this.assertHub();
        return increaseStars(-decrBy);
    }

    @Override
    public long decreasePowders(long decrBy)
    {
        this.assertHub();
        return increasePowders(-decrBy);
    }

    @Override
    public long getCoins()
    {
        refreshIfNeeded();
        return playerBean.getCoins();
    }

    @Override
    public long getStars() {
        refreshIfNeeded();
        return playerBean.getStars();
    }

    @Override
    public long getPowders() {
        refreshIfNeeded();
        return playerBean.getPowders();
    }

    @Override
    public String getDisplayName()
    {
        return hasNickname() ? getCustomName() : getEffectiveName();
    }

    @Override
    public String getCustomName()
    {
        return playerBean.getNickName();
    }

    @Override
    public String getEffectiveName() {
        return playerBean.getName();
    }

    @Override
    public UUID getPlayerID() {
        return playerUUID;
    }

    @Override
    public Date getLastRefresh() {
        return new Date(lastRefresh);
    }


    /**
     *  Need to be call before edit data
     */
    public void refreshIfNeeded()
    {
        if (lastRefresh + 1000 * 60 < System.currentTimeMillis())
        {
            refreshData();
        }
    }

    public PlayerBean getPlayerBean()
    {
        return playerBean;
    }

    public boolean hasNickname()
    {
        return this.getCustomName() != null && !this.getCustomName().equals("null");
    }

    public UUID getFakeUUID() {
        return fakeUUID;
    }

    public GameProfile getFakeProfile() {
        return fakeProfile;
    }

    public void applyNickname(Player player)
    {
        try {
            Object craftPlayer = Reflection.getHandle(player);
            Method getProfileMethod = craftPlayer.getClass().getMethod("getProfile");

            GameProfile profile = (GameProfile) getProfileMethod.invoke(craftPlayer);
            Field name = GameProfile.class.getDeclaredField("name");
            Reflection.setFinal(profile, name, getDisplayName());
        } catch (ReflectiveOperationException e) {
            e.printStackTrace();
        }
    }

    private void assertHub()
    {
        StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
        boolean isHub = false;

        for (StackTraceElement stackTraceElement : stackTraceElements)
        {
            if (stackTraceElement.getClassName().contains("hub"))
            {
                isHub = true;
                break;
            }
        }

        if (!isHub)
        {
            throw new UnsupportedOperationException("You don't have the permission to use this method! Maybe it's now deprecated or for private use only.");
        }
    }
}
