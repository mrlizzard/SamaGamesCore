package net.samagames.core.api.games.pearls;

import com.google.gson.Gson;
import net.samagames.api.games.pearls.IPearlManager;
import net.samagames.api.games.pearls.Pearl;
import net.samagames.core.ApiImplementation;
import net.samagames.tools.chat.ChatUtils;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import redis.clients.jedis.Jedis;

import java.util.Calendar;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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
public class PearlManager implements IPearlManager
{
    private enum RankChances
    {
        NORMAL(20, 25, 20, 20),
        VIP(25, 25, 25, 15),
        VIPPLUS(25, 25, 20, 15);

        private final int oneStarPercentage;
        private final int twoStarsPercentage;
        private final int threeStarsPercentage;
        private final int fourStarsPercentage;

        RankChances(int oneStarPercentage, int twoStarsPercentage, int threeStarsPercentage, int fourStarsPercentage)
        {
            this.oneStarPercentage = oneStarPercentage;
            this.twoStarsPercentage = twoStarsPercentage;
            this.threeStarsPercentage = threeStarsPercentage;
            this.fourStarsPercentage = fourStarsPercentage;
        }

        /**
         * Return a randomized stars count calculated with
         * the percentages.
         *
         * Note: We don't need a 5 stars percentage because of
         * the total of the percentage have to be equals to 100
         * (obvious).
         *
         * @return A randomized stars count
         */
        public int getRandomizedStars()
        {
            int random = new Random().nextInt(100);

            if (random <= this.oneStarPercentage)
                return 1;
            else if (random <= this.oneStarPercentage + this.twoStarsPercentage)
                return 2;
            else if (random <= this.oneStarPercentage + this.twoStarsPercentage + this.threeStarsPercentage)
                return 3;
            else if (random <= this.oneStarPercentage + this.twoStarsPercentage + this.threeStarsPercentage + this.fourStarsPercentage)
                return 4;
            else
                return 5;
        }

        public static RankChances getByRankId(int rankId)
        {
            if (rankId == 1)
                return NORMAL;
            else if (rankId == 2)
                return VIP;
            else
                return VIPPLUS;
        }
    }

    private final ApiImplementation api;

    public PearlManager(ApiImplementation api)
    {
        this.api = api;
    }

    @Override
    public Pearl runGiveAlgorythm(Player player, int gameTime, boolean win)
    {
        int playerRankId = (int) this.api.getPlayerManager().getPlayerData(player.getUniqueId()).getPlayerBean().getGroupId();

        double rankMultiplier = playerRankId > 1 ? Double.parseDouble("1." + (playerRankId < 6 ? 5 : playerRankId - 1)) : 1.0D;

        if (gameTime < 10)
            gameTime = 10;

        int pearlChance = (int) ((gameTime / 2) * rankMultiplier * (win ? 1.2 : 0.8));
        int random = new Random().nextInt(100);

        if (random <= pearlChance)
        {
            int stars = RankChances.getByRankId(playerRankId).getRandomizedStars();

            Calendar calendar = Calendar.getInstance();
            calendar.add(Calendar.DAY_OF_MONTH, 7);

            Pearl pearl = new Pearl(UUID.randomUUID(), stars, calendar.getTime().getTime());

            Jedis jedis = this.api.getBungeeResource();
            jedis.set("pearls:" + player.getUniqueId().toString() + ":" + pearl.getUUID().toString(), new Gson().toJson(pearl));
            jedis.expire("pearls:" + player.getUniqueId().toString() + ":" + pearl.getUUID().toString(), (int) TimeUnit.MILLISECONDS.toSeconds(pearl.getExpiration()));
            jedis.close();

            return pearl;
        }

        return null;
    }
}
